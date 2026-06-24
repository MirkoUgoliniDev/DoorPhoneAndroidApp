package com.doorphone.service;



import se.lublin.humla.IHumlaService;




public interface IDoorPhoneService extends IHumlaService {
    void setOverlayShown(boolean showOverlay);

    boolean isOverlayShown();

    void clearChatNotifications();

    void markErrorShown();

    boolean isErrorShown();

    void onTalkKeyDown();

    void onTalkKeyUp();

    void setSuppressNotifications(boolean suppressNotifications);

    void closeCall();

    void openCall();

    boolean isDoorCallActive();

}
