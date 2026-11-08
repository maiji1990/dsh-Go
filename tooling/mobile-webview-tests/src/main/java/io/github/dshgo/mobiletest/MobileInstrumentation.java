package io.github.dshgo.mobiletest;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** A standalone test APK: uses the production assets, never the user's app/data. */
public class MobileInstrumentation extends Instrumentation {
    private TestActivity activity;
    private final JSONArray checks = new JSONArray();
    private String asset(String path) throws Exception {
        try (java.io.InputStream in = getTargetContext().getAssets().open(path)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int n; while ((n=in.read(buffer))!=-1) bytes.write(buffer,0,n);
            return bytes.toString("UTF-8");
        }
    }
    private String js(String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> answer = new AtomicReference<>();
        runOnMainSync(() -> activity.web.evaluateJavascript(script, value -> { answer.set(value); done.countDown(); }));
        if (!done.await(8, TimeUnit.SECONDS)) throw new AssertionError("JS timed out");
        return answer.get();
    }
    private void check(String name, String expression) throws Exception {
        String actual = js("(()=>{try{return Boolean(" + expression + ")}catch(e){return false}})()");
        checks.put(new JSONObject().put("name", name).put("pass", "true".equals(actual)));
        if (!"true".equals(actual)) throw new AssertionError(name);
    }
    private void settle() throws Exception {
        for (int i=0;i<150;i++) {
            if ("true".equals(js("!window.c || c.diagnostics().pending===0"))) { SystemClock.sleep(80); return; }
            SystemClock.sleep(32);
        }
        throw new AssertionError("adapter did not settle");
    }
    private void width(int width) throws Exception {
        float density = activity.getResources().getDisplayMetrics().density;
        runOnMainSync(() -> {
            ViewGroup.LayoutParams params = activity.web.getLayoutParams();
            params.width = Math.round(width*density);
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            activity.web.setLayoutParams(params);
        });
        SystemClock.sleep(100);
        settle();
    }
    private void load(String content) throws Exception { load(content, true); }
    private void load(String content, boolean islandBoundary) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        String html = "<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'><style>body{margin:0}*{box-sizing:border-box}</style>"+content;
        runOnMainSync(() -> {
            activity.web.setWebViewClient(new WebViewClient() { @Override public void onPageFinished(android.webkit.WebView web, String url) { loaded.countDown(); } });
            activity.web.loadDataWithBaseURL("https://mobile-test.invalid/", html, "text/html", "UTF-8", null);
        });
        if (!loaded.await(10,TimeUnit.SECONDS)) throw new AssertionError("page load");
        if (islandBoundary) {
            js("document.documentElement.setAttribute('data-dsh-go-island','page');document.body.setAttribute('data-dsh-go-island','frame')");
            js("(()=>{let s=document.createElement('style');s.textContent="+JSONObject.quote(asset("island_mobile.css"))+";document.head.append(s)})()");
        }
        js(asset("mobile_layout.js"));
        js("(()=>{let s=document.createElement('style');s.textContent="+JSONObject.quote(asset("island_mobile_components.css"))+";document.head.append(s)})()");
    }
    private void screenshot(String name) throws Exception {
        // Render only this test WebView. Never capture the device screen,
        // notifications, another app, or the user's data.
        AtomicReference<Bitmap> rendered = new AtomicReference<>();
        runOnMainSync(() -> {
            Bitmap bitmap = Bitmap.createBitmap(activity.web.getWidth(), activity.web.getHeight(), Bitmap.Config.ARGB_8888);
            activity.web.draw(new android.graphics.Canvas(bitmap));
            rendered.set(bitmap);
        });
        Bitmap bitmap=rendered.get();
        File file = new File(getTargetContext().getFilesDir(), name+".png");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG,100,out); }
        bitmap.recycle();
    }
    private void tapElement(String id) throws Exception {
        js("document.getElementById('"+id+"').scrollIntoView({block:'center'})");
        SystemClock.sleep(250);
        waitForIdleSync();
        JSONArray rect = new JSONArray(js("(()=>{let r=document.getElementById('"+id+"').getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2]})()"));
        int[] location = new int[2];runOnMainSync(()->activity.web.getLocationOnScreen(location));
        float density=activity.getResources().getDisplayMetrics().density;
        float x=location[0]+(float)rect.getDouble(0)*density, y=location[1]+(float)rect.getDouble(1)*density;
        long t=SystemClock.uptimeMillis();
        sendPointerSync(android.view.MotionEvent.obtain(t,t,0,x,y,0));
        sendPointerSync(android.view.MotionEvent.obtain(t,t+50,1,x,y,0));
        SystemClock.sleep(200);
    }
    private void commitText(String value) throws Exception {
        AtomicReference<android.view.inputmethod.InputConnection> connection=new AtomicReference<>();
        runOnMainSync(()->connection.set(activity.web.onCreateInputConnection(new android.view.inputmethod.EditorInfo())));
        if(connection.get()==null)throw new AssertionError("no native input connection");
        CountDownLatch done=new CountDownLatch(1);
        Runnable commit=()->{connection.get().commitText(value,1);done.countDown();};
        android.os.Handler handler=connection.get().getHandler();
        if(handler!=null)handler.post(commit);else runOnMainSync(commit);
        if(!done.await(3,TimeUnit.SECONDS))throw new AssertionError("native input timeout");
        SystemClock.sleep(150);
    }
    private void back() {
        long now=SystemClock.uptimeMillis();
        getUiAutomation().injectInputEvent(new android.view.KeyEvent(now,now,android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_BACK,0),true);
        getUiAutomation().injectInputEvent(new android.view.KeyEvent(now,now+30,android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_BACK,0),true);
    }
    private void inputs() throws Exception {
        load(asset("mobile_inputs.html"));
        js("window.c=DshGoMobileLayout.create(document.body,{});c.start()");
        width(360);
        runOnMainSync(()->activity.web.getSettings().setTextZoom(200));
        for(String id:new String[]{"text","password","email","url","tel","search","number","textarea"}) {
            tapElement(id);
            check("native focus/"+id,"document.activeElement.id===\""+id+"\"");
            commitText("12345");
            SystemClock.sleep(150);
            check("native input/"+id,"document.getElementById('"+id+"').value==='12345'");
            check("focused control visible/"+id,"(()=>{let r=document.getElementById('"+id+"').getBoundingClientRect();return r.top>=0 && r.bottom<=(visualViewport?visualViewport.height:innerHeight)+1})()");
        }
        back();
        SystemClock.sleep(200);
        check("keyboard back preserves values","document.getElementById('textarea').value==='12345'");
        js("document.getElementById('text').focus();document.getElementById('text').setSelectionRange(1,4)");
        check("selection range","document.getElementById('text').selectionStart===1 && document.getElementById('text').selectionEnd===4");
        js("document.activeElement.blur()");
        runOnMainSync(()->((android.view.inputmethod.InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(activity.web.getWindowToken(),0));
        SystemClock.sleep(350);
        for(String id:new String[]{"select","date","time","color","file"}) {
            tapElement(id);
            back();
            SystemClock.sleep(150);
            check("picker returns/"+id,"document.getElementById('"+id+"')!==null");
        }
        tapElement("checkbox");check("checkbox usable","document.getElementById('checkbox').checked");
        tapElement("radio");check("radio usable","document.getElementById('radio').checked");
        tapElement("save");check("save accessible","document.getElementById('save').dataset.saved==='yes'");
        screenshot("mobile_inputs_2x");
        runOnMainSync(()->activity.web.getSettings().setTextZoom(100));
    }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        boolean ok=false;
        try {
            Intent launch = new Intent(getTargetContext(),TestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = (TestActivity)startActivitySync(launch);
            for (String fixture : new String[]{"mobile_form.html","mobile_table.html","mobile_dialog_tabs.html"}) {
                load(asset(fixture));
                js("window.c=DshGoMobileLayout.create(document.body,{mode:'automatic'});c.start()");
                for (int w : new int[]{320,360,393,480,600,768,1024,360}) {
                    width(w);
                    check(fixture+"/"+w+"/settled", "c.diagnostics().pending===0 && c.diagnostics().maxBatchNodes<=200");
                    if (fixture.equals("mobile_form.html")) check("form direction/"+w,"getComputedStyle(document.querySelector('.row')).flexDirection==='"+(w<480?"column":"row")+"'");
                    if (fixture.equals("mobile_table.html")) check("grid columns/"+w,"getComputedStyle(document.querySelector('.cards')).gridTemplateColumns.split(' ').length==="+(w<480?1:3));
                }
                if (fixture.equals("mobile_form.html")) {
                    check("fixed page fits frame", "document.querySelector('.page').getBoundingClientRect().right<=innerWidth+1");
                    check("toolbar wraps in frame", "getComputedStyle(document.querySelector('.toolbar')).flexWrap==='wrap' && document.querySelector('.toolbar').scrollWidth<=innerWidth+1");
                }
                if (fixture.equals("mobile_table.html")) check("table keeps columns within scrollport", "document.querySelector('table').clientWidth<=innerWidth && document.querySelector('table').scrollWidth>document.querySelector('table').clientWidth");
                screenshot(fixture.replace(".html",""));
                js("c.stop()");
                check("rollback/"+fixture,"!document.querySelector('[data-dsh-mobile-role],[data-dsh-mobile-compact],[data-dsh-mobile-root],[data-dsh-go-mobile-wide]')");
            }
            load("<div id='plugin' data-slot='settings.section'><div class='row' style='display:flex'><label>Field</label><input></div></div><nav id='official' style='display:flex'><label>Official</label><input></nav>", false);
            js(asset("plugin_mobile.js"));
            js("document.documentElement.setAttribute('data-dsh-app-layout','drawer')");
            SystemClock.sleep(200);
            check("web slot adapted","document.querySelector('#plugin .row').getAttribute('data-dsh-mobile-role')==='form-row'");
            check("official untouched","!document.querySelector('#official [data-dsh-mobile-role]') && !document.getElementById('official').hasAttribute('data-dsh-mobile-role')");
            js("document.getElementById('plugin').style.display='none'");
            SystemClock.sleep(100);
            check("hidden slot stops controller","__dshGoPluginMobile.inspect().length===0");
            js("document.getElementById('plugin').style.display='block'");
            SystemClock.sleep(100);
            check("visible slot resumes controller","__dshGoPluginMobile.inspect().length===1");
            js("document.documentElement.removeAttribute('data-dsh-app-layout')");
            SystemClock.sleep(100);
            check("web layout off cleans markers","!document.querySelector('[data-dsh-mobile-role],[data-dsh-mobile-root]')");
            js("document.documentElement.setAttribute('data-dsh-app-layout','drawer')");
            SystemClock.sleep(100);
            js("document.getElementById('plugin').remove()");
            SystemClock.sleep(100);
            check("web slot removal releases controllers","__dshGoPluginMobile.inspect().length===0");
            load("<main id='root'></main>");
            js("let root=document.getElementById('root'); for(let i=0;i<1500;i++)root.append(document.createElement('p')); let tabs=document.createElement('div');tabs.setAttribute('role','tablist');root.append(tabs);window.c=DshGoMobileLayout.create(root,{});c.start()");
            settle();
            check("beyond 1200 nodes","document.querySelector('[role=tablist]').getAttribute('data-dsh-mobile-role')==='tablist'");
            check("bounded batch","c.diagnostics().maxBatchNodes<=200 && c.diagnostics().batches>1");
            js("let row=document.createElement('div');row.id='dynamic';row.style.display='flex';row.innerHTML='<label>Dynamic</label><input>';document.getElementById('root').append(row)");
            SystemClock.sleep(150);settle();
            check("dynamic DOM","document.getElementById('dynamic').getAttribute('data-dsh-mobile-role')==='form-row'");
            js("window.removed=document.getElementById('dynamic');removed.remove()");
            SystemClock.sleep(100);
            check("removed nodes release markers","!removed.hasAttribute('data-dsh-mobile-role')");
            for (int i=0;i<100;i++) js("c.stop();c.start()");
            settle();check("100 restarts","c.diagnostics().pending===0");
            for (int i=0;i<30;i++) {
                final int orientation=i%2==0?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                runOnMainSync(()->activity.setRequestedOrientation(orientation));
                int expected=i%2==0?android.content.res.Configuration.ORIENTATION_LANDSCAPE:android.content.res.Configuration.ORIENTATION_PORTRAIT;
                for(int retry=0;retry<60 && activity.getResources().getConfiguration().orientation!=expected;retry++) SystemClock.sleep(50);
                if(activity.getResources().getConfiguration().orientation!=expected)throw new AssertionError("rotation did not apply");
            }
            settle();check("30 rotations","c.diagnostics().pending===0");
            load("<div role='dialog' data-dsh-go-island='layer' id='modal'>Synthetic modal</div><dialog open id='native'>Native dialog</dialog>");
            js(asset("island.js"));
            js("(()=>{let modal=document.getElementById('modal');modal.addEventListener('keydown',e=>{if(e.key==='Escape')setTimeout(()=>modal.remove(),32)})})()");
            check("back consumes root portal dialog", "__dshGoIsland.back()===true");
            SystemClock.sleep(100);
            check("plugin closes dialog asynchronously", "!document.getElementById('modal')");
            js("window.c=DshGoMobileLayout.create(document.body,{});c.start()");
            settle();
            check("native dialog has semantic layout", "document.getElementById('native').getAttribute('data-dsh-mobile-role')==='dialog'");
            inputs();
            ok=true;
        } catch(Throwable e) {
            result.putString("failure",e.toString());
            try {
                screenshot("failure-fixture");
                result.putString("fixtureState",js("JSON.stringify({focus:document.activeElement.id,width:innerWidth,height:innerHeight,dpr:devicePixelRatio,len:document.getElementById('text')?.value.length})"));
            } catch(Exception ignored) {}
        }
        try {
            JSONObject report=new JSONObject().put("passed",ok).put("sdk",android.os.Build.VERSION.SDK_INT)
                .put("webView",android.webkit.WebView.getCurrentWebViewPackage().versionName).put("checks",checks);
            JSONObject hashes=new JSONObject();
            for(String name:new String[]{"mobile_layout.js","island.js","island_mobile_components.css","island_mobile.css","plugin_mobile.js"}) {
                byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(asset(name).getBytes(StandardCharsets.UTF_8));
                StringBuilder hex=new StringBuilder();for(byte b:hash)hex.append(String.format("%02x",b));hashes.put(name,hex.toString());
            }
            report.put("assets",hashes);
            File reportFile=new File(getTargetContext().getFilesDir(),"report.json");
            try(FileOutputStream out=new FileOutputStream(reportFile)){out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}
            result.putString("report",reportFile.getAbsolutePath());
            result.putInt("checks",checks.length());
        } catch(Exception e) {result.putString("reportError",e.toString());}
        finish(ok?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
