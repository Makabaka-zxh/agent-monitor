# Only local Java compilation; the platform doubles and classes stay under the D work directory.
param(
    [string]$OutputDirectory = 'D:/CodexData/Projects/agent-monitor/work/rc13-launch-fix-20261110/lifecycle-test-01',
    [string]$JavaDirectory = 'D:/APP/Scoop/apps/temurin17-jdk/17.0.20-8'
)
$ErrorActionPreference = 'Stop'
$testOutput = [IO.Path]::GetFullPath($OutputDirectory)
$testRoot = [IO.Path]::GetFullPath('D:/CodexData/Projects/agent-monitor/work') + [IO.Path]::DirectorySeparatorChar
if (-not $testOutput.StartsWith($testRoot, [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $testOutput)) {
    throw 'Use a new test output directory under the project D work directory.'
}
$androidSource = Join-Path (Split-Path $PSScriptRoot -Parent) 'src/com/agentmonitor/live'
$nativeSource = Join-Path $androidSource 'NativeSystemBars.java'
$policySource = Join-Path $androidSource 'NativeSystemBarPolicy.java'
$oldSource = 'D:/CodexData/Projects/agent-monitor/work/appearance-refresh-20261110/candidate-1.0.7-rc.13-code48/source/android/src/com/agentmonitor/live/NativeSystemBars.java'
$oldHash = '8cd28dc2fdaf0d3e02ed26a89963df14aae38ba378466d2ebc56c5e7d3671600'
if ((Get-FileHash -LiteralPath $oldSource -Algorithm SHA256).Hash.ToLowerInvariant() -ne $oldHash) { throw 'Frozen RC13 helper changed.' }
$sourcePaths = [ordered]@{
    'android/src/com/agentmonitor/live/NativeSystemBars.java' = $nativeSource
    'android/src/com/agentmonitor/live/NativeSystemBarPolicy.java' = $policySource
    'android/tests/Test-NativeSystemBarsLifecycle.ps1' = $PSCommandPath
}
$sourceHashes = [ordered]@{}
foreach ($name in $sourcePaths.Keys) { $sourceHashes[$name] = (Get-FileHash -LiteralPath $sourcePaths[$name] -Algorithm SHA256).Hash.ToLowerInvariant() }
New-Item -ItemType Directory -Path $testOutput | Out-Null
$encoding = [Text.UTF8Encoding]::new($false)
function Write-TestSource([string]$Relative, [string]$Text) {
    $path = Join-Path $testOutput $Relative
    New-Item -ItemType Directory -Path (Split-Path $path -Parent) -Force | Out-Null
    [IO.File]::WriteAllText($path, $Text, $encoding)
    return $path
}
$stubSources = @{
    'android/os/Build.java' = 'package android.os; public final class Build { public static final class VERSION { public static int SDK_INT=36; } }'
    'android/graphics/Color.java' = 'package android.graphics; public final class Color { public static final int TRANSPARENT=0; }'
    'android/graphics/ColorFilter.java' = 'package android.graphics; public class ColorFilter {}'
    'android/graphics/PixelFormat.java' = 'package android.graphics; public final class PixelFormat { public static final int TRANSLUCENT=-3; }'
    'android/graphics/Paint.java' = 'package android.graphics; public class Paint { public int color; public void setColor(int value) { color=value; } }'
    'android/graphics/Rect.java' = 'package android.graphics; public class Rect { public int left,top,right,bottom; public int width(){return right-left;} public int height(){return bottom-top;} }'
    'android/graphics/Insets.java' = 'package android.graphics; public class Insets { public final int left,top,right,bottom; public Insets(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;} }'
    'android/graphics/Canvas.java' = 'package android.graphics; public class Canvas { public int lastColor; public float lastBottom; public int draws; public void drawRect(float l,float t,float r,float b,Paint paint){draws++;lastColor=paint.color;lastBottom=b;} }'
    'android/graphics/drawable/Drawable.java' = 'package android.graphics.drawable; import android.graphics.*; public abstract class Drawable { private final Rect bounds=new Rect(); public Rect getBounds(){return bounds;} public void setBounds(int l,int t,int r,int b){bounds.left=l;bounds.top=t;bounds.right=r;bounds.bottom=b;} public void invalidateSelf(){} public abstract void draw(Canvas canvas); public abstract void setAlpha(int alpha); public abstract void setColorFilter(ColorFilter filter); public abstract int getOpacity(); }'
    'android/view/DisplayCutout.java' = 'package android.view; public class DisplayCutout { public int getSafeInsetTop(){return 30;} public int getSafeInsetLeft(){return 0;} public int getSafeInsetRight(){return 0;} }'
    'android/view/WindowManager.java' = 'package android.view; public interface WindowManager { public static class LayoutParams { public static final int FLAG_TRANSLUCENT_STATUS=1, FLAG_TRANSLUCENT_NAVIGATION=2, FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS=4; } }'
    'android/view/WindowInsetsController.java' = 'package android.view; public class WindowInsetsController { public static final int APPEARANCE_LIGHT_STATUS_BARS=8, APPEARANCE_LIGHT_NAVIGATION_BARS=16; public int appearance=128,calls,lastMask; public void setSystemBarsAppearance(int value,int mask){calls++;lastMask=mask;appearance=(appearance&~mask)|(value&mask);} }'
    'android/view/ViewOverlay.java' = 'package android.view; import java.util.*; import android.graphics.drawable.Drawable; public class ViewOverlay { public final List<Drawable> values=new ArrayList<>(); public void add(Drawable value){values.add(value);} }'
    'android/view/WindowInsets.java' = @'
package android.view;
import android.graphics.Insets;
public class WindowInsets {
    public static class Type {
        public static int statusBars(){return 1;} public static int captionBar(){return 2;}
        public static int displayCutout(){return 4;} public static int navigationBars(){return 8;}
    }
    public Insets getInsets(int type){if(android.os.Build.VERSION.SDK_INT<30)throw new AssertionError("API30 insets");return type==8?new Insets(0,0,0,32):new Insets(0,24,0,0);}
    public int getSystemWindowInsetTop(){return 24;} public int getSystemWindowInsetLeft(){return 0;}
    public int getSystemWindowInsetRight(){return 0;} public int getSystemWindowInsetBottom(){return 320;}
    public int getStableInsetBottom(){return 32;}
    public DisplayCutout getDisplayCutout(){if(android.os.Build.VERSION.SDK_INT<28)throw new AssertionError("API28 cutout");return null;}
}
'@
    'android/view/View.java' = @'
package android.view;
import java.util.*;
public class View {
    public static final int SYSTEM_UI_FLAG_LAYOUT_STABLE=256,SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN=1024,SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION=512,SYSTEM_UI_FLAG_LIGHT_STATUS_BAR=8192,SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR=16;
    public boolean attached; public int controllerReads,visibility=64,requests; public WindowInsetsController controller;
    public final ViewOverlay overlay=new ViewOverlay();
    private final List<OnAttachStateChangeListener> listeners=new ArrayList<>();
    public boolean isAttachedToWindow(){return attached;}
    public WindowInsetsController getWindowInsetsController(){controllerReads++;if(android.os.Build.VERSION.SDK_INT<30)throw new AssertionError("API30 root getter");if(!attached)throw new NullPointerException("unattached root");return controller;}
    public ViewOverlay getOverlay(){return overlay;}
    public int getWidth(){return 400;} public int getHeight(){return 800;}
    public int getSystemUiVisibility(){return visibility;} public void setSystemUiVisibility(int value){visibility=value;}
    public void requestApplyInsets(){requests++;}
    public void addOnAttachStateChangeListener(OnAttachStateChangeListener listener){listeners.add(listener);}
    public void addOnLayoutChangeListener(OnLayoutChangeListener listener){}
    public void simulateAttach(){attached=true;for(OnAttachStateChangeListener listener:listeners)listener.onViewAttachedToWindow(this);}
    public void simulateDetach(){attached=false;for(OnAttachStateChangeListener listener:listeners)listener.onViewDetachedFromWindow(this);}
    public interface OnAttachStateChangeListener {void onViewAttachedToWindow(View view);void onViewDetachedFromWindow(View view);}
    public interface OnLayoutChangeListener {void onLayoutChange(View view,int l,int t,int r,int b,int oldL,int oldT,int oldR,int oldB);}
}
'@
    'android/view/Window.java' = @'
package android.view;
public class Window {
    private View decor;
    public int controllerReads; public boolean decorFits=true,statusContrast=true,navigationContrast=true;
    public void clearFlags(int flags){} public void addFlags(int flags){}
    public void setDecorFitsSystemWindows(boolean value){if(android.os.Build.VERSION.SDK_INT<30)throw new AssertionError("API30 layout");decorFits=value;}
    public void setStatusBarColor(int color){} public void setNavigationBarColor(int color){}
    public void setNavigationBarDividerColor(int color){if(android.os.Build.VERSION.SDK_INT<28)throw new AssertionError("API28 divider");}
    public void setStatusBarContrastEnforced(boolean value){if(android.os.Build.VERSION.SDK_INT<29)throw new AssertionError("API29 contrast");statusContrast=value;}
    public void setNavigationBarContrastEnforced(boolean value){if(android.os.Build.VERSION.SDK_INT<29)throw new AssertionError("API29 contrast");navigationContrast=value;}
    public View getDecorView(){if(decor==null)decor=new View();return decor;}
    // Model PhoneWindow's direct mDecor access. Color/layout setters do not create decor.
    public WindowInsetsController getInsetsController(){controllerReads++;return decor.getWindowInsetsController();}
}
'@
}
$fixture = @'
package com.agentmonitor.live;
import android.graphics.Canvas;
import android.os.Build;
import android.view.*;
public final class NativeSystemBarsLifecycleTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("lifecycle check "+checks);}
    private static void modern(int sdk){
        Build.VERSION.SDK_INT=sdk;
        Window window=new Window();View root=new View();
        NativeSystemBars bars=NativeSystemBars.attach(window,root,0xffffffff,0xff000000);
        check(window.controllerReads==0);check(root.controllerReads==0);check(!window.decorFits);
        check(!window.statusContrast&&!window.navigationContrast);check(root.overlay.values.size()==1);
        bars.setColors(0xffffffff,0xff000000);bars.onInsets(new WindowInsets());
        check(root.controllerReads==0);check(window.controllerReads==0);
        root.simulateAttach();check(root.controllerReads==1);check(root.controller==null);check(root.requests>=2);
        root.controller=new WindowInsetsController();bars.onInsets(new WindowInsets());
        check(root.controller.calls==1);check(root.controller.lastMask==24);check(root.controller.appearance==(128|8));
        bars.setColors(0xff000000,0xffffffff);check(root.controller.appearance==(128|16));
        bars.setColors(0xff000000,0xff000000);check(root.controller.appearance==128);
        bars.setColors(0xffffffff,0xffffffff);check(root.controller.appearance==(128|24));
        Canvas canvas=new Canvas();root.overlay.values.get(0).draw(canvas);
        check(canvas.draws==4);check(canvas.lastColor==0xffffffff);check(canvas.lastBottom==24);
        int reads=root.controllerReads;root.simulateDetach();
        bars.setColors(0xff000000,0xff000000);bars.onInsets(new WindowInsets());check(root.controllerReads==reads);
        root.simulateAttach();check(root.controller.appearance==128);check(window.controllerReads==0);
    }
    private static void legacy(int sdk){
        Build.VERSION.SDK_INT=sdk;Window window=new Window();View root=new View();
        NativeSystemBars bars=NativeSystemBars.attach(window,root,0xffffffff,0xff000000);
        check(window.controllerReads==0&&root.controllerReads==0);
        int layout=View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        check((window.getDecorView().visibility&layout)==layout);
        int mask=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        check((window.getDecorView().visibility&mask)==View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        bars.setColors(0xff000000,0xffffffff);bars.onInsets(new WindowInsets());root.simulateAttach();
        check((window.getDecorView().visibility&mask)==View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        check((window.getDecorView().visibility&64)==64);
        check(window.controllerReads==0&&root.controllerReads==0);
    }
    private static void old(){
        Build.VERSION.SDK_INT=36;Window window=new Window();boolean failed=false,path=false;
        try{NativeSystemBars.attach(window,new View(),0xffffffff,0xff000000);}
        catch(NullPointerException expected){failed=true;for(StackTraceElement frame:expected.getStackTrace())if(frame.getClassName().equals("com.agentmonitor.live.NativeSystemBars")&&frame.getMethodName().equals("applyAppearance"))path=true;}
        check(failed);check(window.controllerReads==1);check(path);
        System.out.println("RC13PreDecorNpeReproduced: "+checks+" checks passed");
    }
    public static void main(String[] args){
        if(args.length==1&&args[0].equals("old")){old();return;}
        modern(30);modern(36);legacy(26);legacy(28);legacy(29);
        System.out.println("NativeSystemBarsLifecycleTest: "+checks+" checks passed");
    }
}
'@
$report = [ordered]@{ passed=$false; checks=0; old_control_checks=0; compiler_exit_success=$false; test_exit_success=$false; old_predecor_npe_reproduced=$false; source_sha256=$sourceHashes; frozen_rc13_helper_sha256=$oldHash; source_unchanged=$false; phone_accessed=$false; private_configuration_accessed=$false; network_accessed=$false; raw_output_saved=$false; platform_compile_passed=$false }
$phase = 'preparation'
try {
    $stubPaths = @()
    foreach ($name in ($stubSources.Keys | Sort-Object)) { $stubPaths += Write-TestSource ('stubs/' + $name) $stubSources[$name] }
    $fixturePath = Write-TestSource 'fixture/com/agentmonitor/live/NativeSystemBarsLifecycleTest.java' $fixture
    $currentCopy = Write-TestSource 'fixed/NativeSystemBars.java' ([IO.File]::ReadAllText($nativeSource))
    $oldCopy = Write-TestSource 'old/NativeSystemBars.java' ([IO.File]::ReadAllText($oldSource))
    $policyCopy = Write-TestSource 'policy/NativeSystemBarPolicy.java' ([IO.File]::ReadAllText($policySource))
    $javac = Join-Path $JavaDirectory 'bin/javac.exe'; $java = Join-Path $JavaDirectory 'bin/java.exe'
    foreach ($variant in @('old','fixed')) {
        $phase = 'compile_' + $variant
        $classes = Join-Path $testOutput ('classes-' + $variant)
        New-Item -ItemType Directory -Path $classes | Out-Null
        $helper = if ($variant -eq 'old') { $oldCopy } else { $currentCopy }
        $compileArguments = @('-encoding','UTF-8','--release','8','-d',$classes) + $stubPaths + @($fixturePath,$policyCopy,$helper)
        $captured = & $javac @compileArguments 2>&1
        if ($LASTEXITCODE -ne 0) { throw 'lifecycle_compile_failed' }
        $phase = 'test_' + $variant
        $runArguments = @('-cp',$classes,'com.agentmonitor.live.NativeSystemBarsLifecycleTest')
        if ($variant -eq 'old') { $runArguments += 'old' }
        $captured = & $java @runArguments 2>&1
        if ($LASTEXITCODE -ne 0) { throw 'lifecycle_test_failed' }
        $testText = ($captured | ForEach-Object { $_.ToString() }) -join "`n"
        $pattern = if ($variant -eq 'old') { '^RC13PreDecorNpeReproduced: ([1-9][0-9]*) checks passed$' } else { '^NativeSystemBarsLifecycleTest: ([1-9][0-9]*) checks passed$' }
        if ($testText -notmatch $pattern) { throw 'test_count_unverified' }
        if ($variant -eq 'old') { $report.old_control_checks=[int]$Matches[1]; $report.old_predecor_npe_reproduced=$true } else { $report.checks=[int]$Matches[1] }
        $captured=$null; $testText=$null
    }
    $phase = 'platform_compile'
    $platformClasses = Join-Path $testOutput 'platform-classes'
    New-Item -ItemType Directory -Path $platformClasses | Out-Null
    $captured = & $javac -encoding UTF-8 -classpath 'D:/APP/Android/sdk/platforms/android-36.1/android.jar' -d $platformClasses $policyCopy $currentCopy 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'platform_compile_failed' }
    $captured=$null
    foreach ($name in $sourcePaths.Keys) {
        if ((Get-FileHash -LiteralPath $sourcePaths[$name] -Algorithm SHA256).Hash.ToLowerInvariant() -ne $sourceHashes[$name]) { throw 'test_input_changed' }
    }
    if ((Get-FileHash -LiteralPath $oldSource -Algorithm SHA256).Hash.ToLowerInvariant() -ne $oldHash) { throw 'old_control_changed' }
    $report.compiler_exit_success=$true; $report.test_exit_success=$true; $report.platform_compile_passed=$true; $report.source_unchanged=$true; $report.passed=$true
} catch {
    $report['failure_stage']=$phase
} finally {
    $report['limitations']=@('Platform doubles model absent decor and lifecycle ordering; this is not an actual device launch test.','Frozen RC13 is used only as the expected failing control; it is never modified.')
    [IO.File]::WriteAllText((Join-Path $testOutput 'result.json'),($report | ConvertTo-Json -Depth 5),$encoding)
}
[ordered]@{passed=$report.passed;checks=$report.checks;old_predecor_npe_reproduced=$report.old_predecor_npe_reproduced;platform_compile_passed=$report.platform_compile_passed} | ConvertTo-Json -Compress
if (-not $report.passed) { exit 2 }
