package com.ukulele.monitor;



/**
 * 版本号集中一处，避免出现多个不一致的硬编码副本。
 *
 * <p>这个值必须与 {@code build_apk.ps1} 的 -VersionName 默认值一致，「关于」页直接读取它。
 * 历史版本曾出现这里停留在 3.8.10、而构建脚本已经是 3.10.4 的情况，界面显示的版本号长期过期。
 */

final class AppVersion {

    static final String NAME = "3.19.12";



    private AppVersion() { }

}

