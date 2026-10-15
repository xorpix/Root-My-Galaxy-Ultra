package dev.experimental.rmgu.d2;

/** Fixed identities shared by the controller and its D2-only companion. */
public final class D2Protocol {
    private D2Protocol() {}
    public static final int API = 1;
    public static final String CONTROLLER = "dev.experimental.azhlroot";
    public static final String HELPER = CONTROLLER + ".d2helper";
    public static final String SERVICE = "dev.experimental.rmgu.d2helper.D2ControlService";
    public static final int REQUEST = 1;
    public static final int RESPONSE = 2;
    public static final String PERMISSION = CONTROLLER + ".permission.CONTROL_D2_HELPER";
    public static final String ACTIVATION = "dev.experimental.rmgu.d2helper.ActivateActivity";
    public static final String ASSET = "d2/helper.apk";
    public static final String METADATA = "d2/helper.json";
    public static final String SOURCE_METADATA = HELPER + ".source";
    public static final String DIRECTORY = "/data/system/rmgu-d2-setup";
    public static final String SETTINGS_FILE = "/data/system/rmgu-d2-settings.bin";
}
