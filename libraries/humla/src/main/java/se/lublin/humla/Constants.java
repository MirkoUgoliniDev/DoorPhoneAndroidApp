package se.lublin.humla;



public class Constants {
    public static final int PROTOCOL_MAJOR = 1;
    public static final int PROTOCOL_MINOR = 2;
    public static final int PROTOCOL_PATCH = 5;

    public static final int TRANSMIT_VOICE_ACTIVITY = 0;
    public static final int TRANSMIT_PUSH_TO_TALK = 1;
    public static final int TRANSMIT_CONTINUOUS = 2;

    public static final int PROTOCOL_VERSION = (PROTOCOL_MAJOR << 16) | (PROTOCOL_MINOR << 8) | PROTOCOL_PATCH;
    public static final String PROTOCOL_STRING = PROTOCOL_MAJOR+ "." +PROTOCOL_MINOR+"."+PROTOCOL_PATCH;
    public static final int DEFAULT_PORT = 64738;

    public static final String TAG = "Humla";
}
