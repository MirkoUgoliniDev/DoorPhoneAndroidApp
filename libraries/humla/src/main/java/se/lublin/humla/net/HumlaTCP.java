/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.humla.net;

import android.net.SSLCertificateSocketFactory;
import android.os.Build;
import android.util.Log;

import com.google.protobuf.Message;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;

import se.lublin.humla.Constants;
import se.lublin.humla.util.HumlaException;

/**
 * Class to maintain and interface with the TCP connection to a Mumble server.
 * Parses Mumble protobuf packets according to the Mumble protocol specification.
 */
public class HumlaTCP extends HumlaNetworkThread {

    /**
     * @brief [DoorPhone] Timeout di lettura del socket TCP, in millisecondi.
     *
     * Senza questo timeout {@code readShort()} resta bloccato all'infinito se la rete
     * cade in modo "silenzioso" (half-open, senza FIN/RST): la caduta verrebbe rilevata
     * solo dal keepalive TCP del sistema operativo (default ~2 ore). Con il timeout la
     * lettura sblocca con una {@link java.net.SocketTimeoutException} (sottoclasse di
     * {@link IOException}) che viene gestita come errore di connessione e innesca
     * l'auto-reconnect.
     *
     * Su una connessione sana c'è traffico almeno ogni 5s (ping), quindi 40s è un valore
     * ampiamente sicuro che non causa falsi positivi. Funge da rete di sicurezza al
     * watchdog applicativo di {@code HumlaConnection} (timeout ping a 30s).
     */
    private static final int SOCKET_READ_TIMEOUT_MS = 40000;

    private final HumlaSSLSocketFactory mSocketFactory;
    private String mHost;
    private int mPort;
    private boolean mUseTor;
    private SSLSocket mTCPSocket;
    private DataInputStream mDataInput;
    private DataOutputStream mDataOutput;
    private boolean mRunning;
    private boolean mConnected;
    private TCPConnectionListener mListener;

    public HumlaTCP(HumlaSSLSocketFactory socketFactory) {
        mSocketFactory = socketFactory;
    }

    public void setTCPConnectionListener(TCPConnectionListener listener) {
        mListener = listener;
    }

    public void connect(String host, int port, boolean useTor) throws ConnectException {
        if(mRunning) throw new ConnectException("TCP connection already established!");
        mHost = host;
        mPort = port;
        mUseTor = useTor;
        startThreads();
    }

    public boolean isRunning() {
        return mRunning;
    }

    public void run() {
        mRunning = true;
        try {
            Log.i(Constants.TAG, "HumlaTCP: Connecting");

            if(mUseTor)
                mTCPSocket = mSocketFactory.createTorSocket(mHost, mPort, HumlaConnection.TOR_HOST, HumlaConnection.TOR_PORT);
            else
                mTCPSocket = mSocketFactory.createSocket(mHost, mPort);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) { // SNI support requires at least API 17
                SSLCertificateSocketFactory scsf = (SSLCertificateSocketFactory) SSLCertificateSocketFactory.getDefault(0);
                scsf.setHostname(mTCPSocket, mHost);
            }

            mTCPSocket.setKeepAlive(true);
            // [DoorPhone] Timeout di lettura: sblocca il read loop se la connessione muore
            // silenziosamente, senza dipendere dal keepalive TCP del SO (~2 ore di default).
            mTCPSocket.setSoTimeout(SOCKET_READ_TIMEOUT_MS);
            mTCPSocket.startHandshake();

            Log.v(Constants.TAG, "HumlaTCP: Started handshake");

            mDataInput = new DataInputStream(mTCPSocket.getInputStream());
            mDataOutput = new DataOutputStream(mTCPSocket.getOutputStream());

            Log.v(Constants.TAG, "HumlaTCP: Now listening");
            mConnected = true;

            if(mListener != null) {
                executeOnMainThread(new Runnable() {
                    @Override
                    public void run() {
                        mListener.onTCPConnectionEstablished();
                    }
                });
            }

            while(mConnected) {
                final short messageType = mDataInput.readShort();
                final int messageLength = mDataInput.readInt();
                final byte[] data = new byte[messageLength];
                mDataInput.readFully(data);

                final HumlaTCPMessageType tcpMessageType = HumlaTCPMessageType.values()[messageType];
                if (mListener != null) {
                    executeOnMainThread(new Runnable() {
                        @Override
                        public void run() {
                            mListener.onTCPMessageReceived(tcpMessageType, messageLength, data);
                        }
                    });
                }
            }
        } catch (SocketException e) {
            error("Could not open a connection to the host", e);
        } catch (SSLHandshakeException e) {
            // Try and verify certificate manually.
            if(mSocketFactory.getServerChain() != null && mListener != null) {
                if(!mRunning) return;
                executeOnMainThread(new Runnable() {
                    @Override
                    public void run() {
                        mListener.onTLSHandshakeFailed(mSocketFactory.getServerChain());
                    }
                });
            } else {
                error("Could not verify host certificate", e);
            }
        } catch (IOException e) {
            error("An error occurred when communicating with the host", e);
        } finally {
            mConnected = false;
            try {
                if (mDataInput != null) mDataInput.close();
                if (mDataOutput != null) mDataOutput.close();
                if (mTCPSocket != null) mTCPSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mRunning = false;

            executeOnMainThread(new Runnable() {
                @Override
                public void run() {
                    mListener.onTCPConnectionDisconnect();
                }
            });
            stopThreads();
        }
    }

    /**
     * Attempts to send a protobuf message over TCP. Thread-safe, executes on a single threaded executor.
     * @param message The message to send.
     * @param messageType The type of the message to send.
     */
    public void sendMessage(final Message message, final HumlaTCPMessageType messageType) {
        executeOnSendThread(new Runnable() {
            @Override
            public void run() {
                if (!HumlaConnection.UNLOGGED_MESSAGES.contains(messageType))
                    Log.v(Constants.TAG, "OUT: " + messageType);
                try {
                    mDataOutput.writeShort(messageType.ordinal());
                    mDataOutput.writeInt(message.getSerializedSize());
                    message.writeTo(mDataOutput);
                } catch (IOException e) {
                    // [DoorPhone] Una scrittura TCP fallita significa socket morto (TCP è
                    // affidabile): propaghiamo l'errore così da innescare l'auto-reconnect
                    // invece di ignorarlo silenziosamente. error() ignora il caso di
                    // disconnessione volontaria (mRunning == false) e l'eventuale doppio
                    // inoltro è assorbito dal guard mExceptionHandled di HumlaConnection.
                    error("Errore di scrittura sul socket TCP", e);
                }
            }
        });
    }
    /**
     * Attempts to send a protobuf message over TCP. Thread-safe, executes on a single threaded executor.
     * @param message The data to send.
     * @param length The length of the byte array.
     * @param messageType The type of the message to send.
     */
    public void sendMessage(final byte[] message, final int length, final HumlaTCPMessageType messageType) {
        executeOnSendThread(new Runnable() {
            @Override
            public void run() {
                if (!HumlaConnection.UNLOGGED_MESSAGES.contains(messageType))
                    Log.v(Constants.TAG, "OUT: " + messageType);
                try {
                    mDataOutput.writeShort(messageType.ordinal());
                    mDataOutput.writeInt(length);
                    mDataOutput.write(message, 0, length);
                } catch (IOException e) {
                    // [DoorPhone] Vedi sendMessage(Message, ...): scrittura fallita = socket
                    // morto, propaghiamo per innescare l'auto-reconnect.
                    error("Errore di scrittura sul socket TCP", e);
                }
            }
        });
    }

    /**
     * Attempts to disconnect gracefully on the Tx thread.
     * Disconnects interrupt the socket listening on the Tx thread, suppressing any exceptions
     * caused by this request. Any remaining protobuf messages will be dispatched first.
     *
     * Suppresses all future errors on this connection.
     */
    public void disconnect() {
        if (!mRunning) return;

        mRunning = false;
        executeOnSendThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mTCPSocket != null)
                        mTCPSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

        if(mListener != null) {
            executeOnMainThread(new Runnable() {
                @Override
                public void run() {
                    mListener.onTCPConnectionDisconnect();
                }
            });
        }
    }

    private void error(String desc, Exception e) {
        if (!mRunning)
            return; // Don't handle errors post-disconnection.
        final HumlaException ce = new HumlaException(desc, e,
                HumlaException.HumlaDisconnectReason.CONNECTION_ERROR);
        if(mListener != null)
            executeOnMainThread(new Runnable() {
                @Override
                public void run() {
                    mListener.onTCPConnectionFailed(ce);
                }
            });
    }

    public interface TCPConnectionListener {
        public void onTCPConnectionEstablished();
        public void onTLSHandshakeFailed(X509Certificate[] chain);
        public void onTCPConnectionFailed(HumlaException e);
        public void onTCPConnectionDisconnect();
        public void onTCPMessageReceived(HumlaTCPMessageType type, int length, byte[] data);
    }
}
