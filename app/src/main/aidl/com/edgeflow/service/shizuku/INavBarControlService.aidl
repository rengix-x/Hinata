package com.edgeflow.service.shizuku;

import android.content.Intent;
import android.os.Bundle;

/**
 * AIDL interface executed inside Shizuku's adb-privileged UserService process.
 * Transaction code 16777114 is the official Shizuku destroy signal.
 */
interface INavBarControlService {
    void destroy() = 16777114;

    int runCommand(String cmd) = 1;

    String runCommandWithOutput(String cmd) = 2;

    boolean startWithOptions(in Intent intent, in Bundle opts, int userId) = 3;

    int getForegroundTaskId() = 4;

    String getForegroundPackage() = 5;

    boolean moveTaskToFreeform(int taskId) = 6;

    String dumpFreeformApi() = 7;

    boolean launchNativeHyperOsFreeform(String packageName, in Intent launchIntent) = 8;

    boolean floatForegroundNativeHyperOs(String fallbackPackageName, in Intent fallbackLaunchIntent) = 9;

    boolean reviveOwnAccessibilityService(boolean forceToggleRebind) = 10;

    void armDeadManSwitch(IBinder clientToken, boolean restoreNavOnDeath, boolean autoReviveOnDeath) = 11;

    boolean restoreSystemNavigationImmediate() = 12;
}
