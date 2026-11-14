package com.leyue.maidmining;

import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.leyue.maidmining.task.MiningTask;

/**
 * TLM extension entry point.
 * Registers the Mining task with Touhou Little Maid's task manager.
 */
@LittleMaidExtension
public class MaidExtension implements ILittleMaid {
    @Override
    public void addMaidTask(TaskManager taskManager) {
        taskManager.add(new MiningTask());
        MaidMiningMod.LOGGER.info("MaidMining task registered uid={}", MiningTask.UID);
    }
}