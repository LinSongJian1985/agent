package com.daxiang.core.android;

import com.android.ddmlib.*;
import com.daxiang.server.ServerClient;
import com.daxiang.core.MobileDeviceHolder;
import com.daxiang.core.MobileDevice;
import com.daxiang.core.android.scrcpy.Scrcpy;
import com.daxiang.core.android.stf.AdbKit;
import com.daxiang.core.android.stf.Minicap;
import com.daxiang.core.android.stf.MinicapInstaller;
import com.daxiang.core.android.stf.Minitouch;
import com.daxiang.core.android.stf.MinitouchInstaller;
import com.daxiang.core.appium.AppiumServer;
import com.daxiang.model.Device;
import com.daxiang.model.UploadFile;
import com.daxiang.service.MobileService;
import com.daxiang.websocket.MobileDeviceWebSocketSessionPool;
import io.appium.java_client.AppiumDriver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.websocket.Session;
import java.io.File;
import java.io.IOException;
import java.util.Date;

/**
 * Created by jiangyitao.
 */
@Component
@Slf4j
public class AndroidDeviceChangeListener implements AndroidDebugBridge.IDeviceChangeListener {

    // http://appium.github.io/appium/assets/ApiDemos-debug.apk
    private static final String APIDEMOS_APK = "vendor/apk/ApiDemos-debug.apk";

    @Autowired
    private ServerClient serverClient;
    @Autowired
    private MobileService mobileService;

    @Override
    public void deviceConnected(IDevice device) {
        new Thread(() -> androidDeviceConnected(device)).start();
    }

    @Override
    public void deviceDisconnected(IDevice device) {
        new Thread(() -> androidDeviceDisconnected(device)).start();
    }

    @Override
    public void deviceChanged(IDevice device, int changeMask) {
    }

    /**
     * Android设备连接到电脑
     *
     * @param iDevice
     */
    private void androidDeviceConnected(IDevice iDevice) {
        String deviceId = iDevice.getSerialNumber();
        log.info("[android][{}]已连接", deviceId);

        log.info("[android][{}]等待上线", deviceId);
        AndroidUtil.waitForDeviceOnline(iDevice, 5 * 60);
        log.info("[android][{}]已上线", deviceId);

        MobileDevice mobileDevice = MobileDeviceHolder.get(deviceId);
        if (mobileDevice == null) {
            log.info("[android][{}]首次在agent上线", deviceId);

            log.info("[android][{}]启动appium server...", deviceId);
            AppiumServer appiumServer = new AppiumServer();
            appiumServer.start();
            log.info("[android][{}]启动appium server完成，url: {}", deviceId, appiumServer.getUrl());

            log.info("[android][{}]检查是否已接入过server", deviceId);
            Device device = serverClient.getDeviceById(deviceId);
            if (device == null) {
                log.info("[android][{}]首次接入server，开始初始化设备", deviceId);
                try {
                    mobileDevice = initAndroidDevice(iDevice, appiumServer);
                    log.info("[android][{}]初始化设备完成", deviceId);
                } catch (Exception e) {
                    appiumServer.stop();
                    throw new RuntimeException("初始化设备" + deviceId + "出错", e);
                }
            } else {
                log.info("[android][{}]已接入过server", deviceId);
                mobileDevice = new AndroidDevice(device, iDevice, appiumServer);
            }

            AndroidDevice androidDevice = (AndroidDevice) mobileDevice;
            androidDevice.setMinicap(new Minicap(iDevice));
            androidDevice.setMinitouch(new Minitouch(iDevice));
            androidDevice.setAdbKit(new AdbKit(deviceId));
            androidDevice.setScrcpy(new Scrcpy(iDevice));

            MobileDeviceHolder.add(deviceId, mobileDevice);
        } else {
            ((AndroidDevice) mobileDevice).setIDevice(iDevice);
            log.info("[android][{}]非首次在agent上线", deviceId);
        }

        mobileService.saveOnlineDeviceToServer(mobileDevice);
        log.info("[android][{}]androidDeviceConnected处理完成", deviceId);
    }

    /**
     * Android设备断开电脑
     *
     * @param iDevice
     */
    public void androidDeviceDisconnected(IDevice iDevice) {
        String deviceId = iDevice.getSerialNumber();
        log.info("[android][{}]断开连接", deviceId);
        MobileDevice mobileDevice = MobileDeviceHolder.get(deviceId);
        if (mobileDevice == null) {
            return;
        }

        mobileService.saveOfflineDeviceToServer(mobileDevice);

        // 有人正在使用，则断开连接
        Session openedSession = MobileDeviceWebSocketSessionPool.getOpenedSession(deviceId);
        if (openedSession != null) {
            try {
                log.info("[android][{}]sessionId: {}正在使用，关闭连接", deviceId, openedSession.getId());
                openedSession.close();
            } catch (IOException e) {
                log.error("close opened session err", e);
            }
        }

        log.info("[android][{}]androidDeviceDisconnected处理完成", deviceId);
    }

    /**
     * 首次接入系统，初始化Android设备
     */
    private MobileDevice initAndroidDevice(IDevice iDevice, AppiumServer appiumServer) throws Exception {
        String deviceId = iDevice.getSerialNumber();

        Device device = new Device();

        device.setPlatform(MobileDevice.ANDROID);
        device.setCreateTime(new Date());
        device.setId(deviceId);
        device.setSystemVersion(AndroidUtil.getAndroidVersion(AndroidUtil.getSdkVersion(iDevice)));
        device.setName(AndroidUtil.getDeviceName(iDevice));
        device.setCpuInfo(AndroidUtil.getCpuInfo(iDevice));
        device.setMemSize(AndroidUtil.getMemSize(iDevice));

        String resolution = AndroidUtil.getResolution(iDevice); // 720x1280
        String[] res = resolution.split("x");
        device.setScreenWidth(Integer.parseInt(res[0]));
        device.setScreenHeight(Integer.parseInt(res[1]));

        AndroidDevice androidDevice = new AndroidDevice(device, iDevice, appiumServer);

        // 小于android5.0使用stf远程真机方案，否则使用scrcpy方案
        // 小于android5.0初始化driver需要指定app
        if (!androidDevice.greaterOrEqualsToAndroid5()) {
            log.info("[android][{}]开始安装minicap", deviceId);
            MinicapInstaller minicapInstaller = new MinicapInstaller(iDevice);
            minicapInstaller.install();
            log.info("[android][{}]安装minicap成功", deviceId);

            log.info("[android][{}]开始安装minitouch", deviceId);
            MinitouchInstaller minitouchInstaller = new MinitouchInstaller(iDevice);
            minitouchInstaller.install();
            log.info("[android][{}]安装minitouch成功", deviceId);

            // 安装一个测试apk，用于初始化appium driver
            log.info("[android][{}]开始安装{}", deviceId, APIDEMOS_APK);
            androidDevice.installApp(new File(APIDEMOS_APK));
            log.info("[android][{}]安装{}完成", deviceId, APIDEMOS_APK);
        }

        log.info("[android][{}]开始初始化appium", deviceId);
        AppiumDriver appiumDriver = androidDevice.initAppiumDriver();
        log.info("[android][{}]初始化appium完成", deviceId);

        // 截图并上传到服务器
        UploadFile uploadFile = androidDevice.screenshotAndUploadToServer();
        device.setImgPath(uploadFile.getFilePath());

        appiumDriver.quit();

        return androidDevice;
    }
}
