package dev.experimental.rmgu.bootprobe;

final class SetupCommand {
    private SetupCommand(){ }
    static String command(String apk,String mode,String key) {
        if(!apk.startsWith("/data/app/")||apk.indexOf('\0')>=0||apk.contains("\n"))throw new IllegalArgumentException("Unexpected installed APK path");
        if(!java.util.List.of("inspect","prepare","remove").contains(mode)||!key.matches("[0-9a-f]{200,20000}")||key.length()%2!=0)throw new IllegalArgumentException("Invalid setup operation");
        return "CLASSPATH="+quote(apk)+" /system/bin/app_process /system/bin --nice-name=rmgu_boot_setup dev.experimental.rmgu.bootprobe.PrivilegeSetupMain "+mode+" "+key;
    }
    private static String quote(String s){return "'"+s.replace("'","'\\''")+"'";}
}
