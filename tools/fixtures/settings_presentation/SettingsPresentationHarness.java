package dev.busung.s25uroot;

import java.util.Arrays;

/** Runs only presentation decisions; no Android, loader, daemon, or native code is linked. */
public final class SettingsPresentationHarness {
    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }

    public static void main(String[] args) {
        switch (args[0]) {
            case "status":
                require(SettingsPresentation.susfsState(true, true) ==
                    SettingsPresentation.SusfsState.ACTIVE, "enabled and active");
                require(SettingsPresentation.susfsState(false, true) ==
                    SettingsPresentation.SusfsState.ACTIVE_UNTIL_REBOOT, "off must retain active boot state");
                require(SettingsPresentation.susfsState(true, false) ==
                    SettingsPresentation.SusfsState.PENDING, "enabled for a future activation");
                require(SettingsPresentation.susfsState(false, false) ==
                    SettingsPresentation.SusfsState.OFF, "off and inactive");
                break;
            case "availability":
                for (boolean automatic : new boolean[]{false, true}) {
                    for (boolean compatible : new boolean[]{false, true}) {
                        require(SettingsPresentation.canChangeSusfs(automatic, true, compatible) == false,
                            "busy operation must be protected");
                    }
                }
                require(SettingsPresentation.canChangeSusfs(true, false, false),
                    "incompatible device must still permit turning an enabled preference off");
                require(!SettingsPresentation.canChangeSusfs(false, false, false),
                    "incompatible device must not permit enabling");
                require(SettingsPresentation.canChangeSusfs(false, false, true),
                    "compatible device permits enabling");
                require(SettingsPresentation.canChangeSusfs(true, false, true),
                    "compatible device permits disabling");
                break;
            case "settle":
                int[] offered = {0, 30, 60, 90, 120, 180, 300, 600};
                int[] original = offered.clone();
                require(Arrays.equals(new int[]{180, 300, 600},
                    SettingsPresentation.manualSettleChoices(offered, 180)),
                    "choices must match the existing effective minimum");
                require(Arrays.equals(original, offered), "do not mutate the shared choice list");
                require(SettingsPresentation.manualSettleChoices(new int[]{0, 30}, 180).length == 0,
                    "never offer a value below the effective minimum");
                require(Arrays.equals(offered, SettingsPresentation.manualSettleChoices(offered, 0)),
                    "a zero minimum preserves the supplied choices");
                break;
            default:
                throw new AssertionError("Unknown presentation test: " + args[0]);
        }
        System.out.println("Passed presentation case: " + args[0]);
    }
}
