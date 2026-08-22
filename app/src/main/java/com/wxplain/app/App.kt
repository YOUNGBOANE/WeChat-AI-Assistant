package com.wxplain.app

import android.app.Application
import com.topjohnwu.superuser.Shell
import com.wxplain.app.ingest.KeyStore
import com.wxplain.app.wechat.SqlCipherCli

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(15)
        )
        KeyStore.init(this)
        SqlCipherCli.deploy(this)
    }
}
