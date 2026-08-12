package com.leyue.maidmining;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main mod class for Maid Mining.
 * A Touhou Little Maid extension that adds autonomous ore mining task.
 */
@Mod(MaidMiningMod.MOD_ID)
public class MaidMiningMod {
    public static final String MOD_ID = "maidmining";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public MaidMiningMod() {
        MinecraftForge.EVENT_BUS.register(this);
    }
}