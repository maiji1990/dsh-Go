package io.github.dshgo.mobiletest;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.webkit.WebView;
import android.widget.FrameLayout;

public class TestActivity extends Activity {
    public WebView web;
    public FrameLayout frame;
    private android.webkit.ValueCallback<android.net.Uri[]> fileResult;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        frame = new FrameLayout(this);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(false);
        frame.addView(web);
        frame.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override public boolean onShowFileChooser(android.webkit.WebView view, android.webkit.ValueCallback<android.net.Uri[]> callback, FileChooserParams params) {
                if(fileResult!=null)fileResult.onReceiveValue(null);
                fileResult=callback;
                startActivityForResult(params.createIntent(),42);
                return true;
            }
        });
        setContentView(frame);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request,result,data);
        if(request==42 && fileResult!=null) {
            fileResult.onReceiveValue(android.webkit.WebChromeClient.FileChooserParams.parseResult(result,data));
            fileResult=null;
        }
    }
}
