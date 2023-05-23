package rosegold.gumtuneclient.config.pages;

import cc.polyfrost.oneconfig.config.annotations.Switch;

public class RevealHiddenMobsFilter {
    @Switch(
            name = "Sneaky Creepers"

    )
    public static boolean sneakyCreepers = true;
    @Switch(
            name = "Ghosts"

    )
    public static boolean ghost = true;
}
