package dev.experimental.rmgu.bootprobe.shared;

public final class HelperProtocol {
    public static final String VERSION = "0.3.0";
    public static final int VERSION_CODE = 4;
    public static final String CONTROLLER = "dev.experimental.rmgu.bootprobe";
    public static final String HELPER = "dev.experimental.rmgu.boothelper";
    public static final String AUTHORITY = HELPER + ".reports";
    public static final String PERMISSION = CONTROLLER + ".permission.HELPER";
    public static final String DIRECTORY = "/data/system/rmgu-boot-probe";
    private HelperProtocol() { }
}
