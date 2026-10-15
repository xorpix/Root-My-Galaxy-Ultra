package dev.busung.s25uroot.d2.setup;

public final class SetupCommand {
    private SetupCommand(){ }
    public static String command(String apk,String mode,String key) {
        if(!apk.startsWith("/data/app/")||apk.indexOf('\0')>=0||apk.contains("\n"))throw new IllegalArgumentException("Unexpected installed APK path");
        if(!java.util.List.of("prepare","remove").contains(mode)||!key.matches("[0-9a-f]{200,20000}")||key.length()%2!=0)throw new IllegalArgumentException("Invalid setup operation");
        return "CLASSPATH="+quote(apk)+" /system/bin/app_process /system/bin --nice-name=rmgu_d2_setup dev.busung.s25uroot.d2.setup.D2SetupMain "+mode+" "+key;
    }
    private static String quote(String s){return "'"+s.replace("'","'\\''")+"'";}
}
