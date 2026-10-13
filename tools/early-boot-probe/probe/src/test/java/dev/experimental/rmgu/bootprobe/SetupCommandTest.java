package dev.experimental.rmgu.bootprobe;
import org.junit.Test;
import static org.junit.Assert.*;
public class SetupCommandTest {
    @Test public void InstalledPathIsShellQuoted() {String command=SetupCommand.command("/data/app/odd'$(value)/base.apk","inspect","aa".repeat(120));assertTrue(command.contains("'\\''"));assertTrue(command.contains(" inspect "));assertFalse(command.contains("reboot"));}
    @Test(expected=IllegalArgumentException.class) public void ArbitraryOperationIsRejected(){SetupCommand.command("/data/app/base.apk","prepare;reboot","aa".repeat(120));}
    @Test(expected=IllegalArgumentException.class) public void ArbitraryKeyTextIsRejected(){SetupCommand.command("/data/app/base.apk","inspect","$(value)");}
    @Test(expected=IllegalArgumentException.class) public void UntrustedClasspathIsRejected(){SetupCommand.command("/sdcard/base.apk","inspect","aa".repeat(120));}
}
