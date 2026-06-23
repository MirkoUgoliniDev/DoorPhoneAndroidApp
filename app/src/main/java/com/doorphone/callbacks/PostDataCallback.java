package com.doorphone.callbacks;

/**
 * @brief Callback per le chiamate HTTP asincrone verso l'API DoorPi.
 *
 * Implementata dalle Activity che inviano comandi tramite {@link com.doorphone.util.CommandSubmitter}
 * e vogliono essere notificate del risultato.
 */
public interface PostDataCallback {

    /**
     * @brief Invocato quando la risposta HTTP è ricevuta con successo.
     * @param json Stringa JSON restituita dal server DoorPi.
     */
    void onDataReceived(String json);

    /**
     * @brief Invocato in caso di errore nella chiamata HTTP.
     * @param errorMessage Descrizione dell'errore (timeout, host irraggiungibile, ecc.).
     */
    void onError(String errorMessage);
}
