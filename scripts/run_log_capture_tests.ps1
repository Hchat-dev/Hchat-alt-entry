param([string]$JavaHome = 'D:/Java/jdk21')
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$temp = Join-Path ([IO.Path]::GetTempPath()) ('hchat-capture-tests-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
$stubs = @{
    'Context.java' = 'package android.content; public class Context { public Context getApplicationContext(){return this;} }'
    'SharedPreferences.java' = 'package android.content; public interface SharedPreferences { boolean getBoolean(String key, boolean value); }'
    'SystemClock.java' = 'package android.os; public class SystemClock { public static long elapsedRealtime(){return System.nanoTime()/1000000;} }'
    'Process.java' = 'package android.os; public class Process { public static final int THREAD_PRIORITY_BACKGROUND=10; public static void setThreadPriority(int p){} public static int myPid(){return 123;} }'
    'HchatStorage.java' = 'package h.Hchat.preferences; public class HchatStorage { public static android.content.SharedPreferences preferences(android.content.Context c,String n){return (k,v)->v;} public static java.io.File storageDir(android.content.Context c){return new java.io.File(System.getProperty("java.io.tmpdir"));} }'
    'XposedBridge.java' = 'package de.robv.android.xposed; public class XposedBridge { public static volatile boolean fail; public static void log(String s){if(fail)throw new LinkageError("桥不可用");} public static void log(Throwable t){if(fail)throw new LinkageError("桥不可用");} }'
}
foreach ($name in $stubs.Keys) {
    [IO.File]::WriteAllText((Join-Path $temp $name), $stubs[$name], [Text.UTF8Encoding]::new($false))
}
$sources = @(Get-ChildItem $temp -Filter '*.java' | ForEach-Object FullName)
$sources += "$project/app/src/main/java/h/Hchat/utils/HLog.java"
$sources += "$project/app/src/main/java/h/Hchat/utils/LogCaptureStore.java"
$sources += "$project/scripts/tests/log_capture/LogCaptureRegression.java"
& "$JavaHome/bin/javac.exe" -encoding UTF-8 -d $temp @sources
if ($LASTEXITCODE -ne 0) { throw '日志回归编译失败' }
& "$JavaHome/bin/java.exe" -cp $temp h.Hchat.utils.LogCaptureRegression
if ($LASTEXITCODE -ne 0) { throw '日志采集回归失败' }