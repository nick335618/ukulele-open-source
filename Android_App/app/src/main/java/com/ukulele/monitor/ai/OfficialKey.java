package com.ukulele.monitor.ai;

/**
 * 官方通道（开发者 Key）占位。
 *
 * <p>真实 Key 不进入源码仓库：Lead 的 build_apk.ps1 在构建暂存目录里把
 * {@link #DEVELOPER_KEY} 替换成实际值后再编译。本类刻意<b>不读取任何外部文件</b>，
 * 这样源码包里永远不含密钥，个人开发者也能直接构建出「仅支持个人 Key」的安装包。
 *
 * <p>未注入时为空串，{@link AiCredentials#activeKey()} 会返回空串，
 * 界面据此提示「当前安装包未配置官方密钥，请联系开发者或用个人 Key」。
 */
public final class OfficialKey {

    /** 官方通道 Key；保持空串占位，由构建脚本注入。 */
    public static final String DEVELOPER_KEY = "";

    private OfficialKey() {
    }
}
