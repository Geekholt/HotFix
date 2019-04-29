package com.geekholt.hotfix;

import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private Button mBtnBugFix;
    private Button mBtnEvent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mBtnBugFix = findViewById(R.id.btn_bug_fix);
        mBtnEvent = findViewById(R.id.btn_event);
        mBtnBugFix.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FixDexUtils.loadFixedDex(MainActivity.this);
            }
        });

        mBtnEvent.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                TestBug testBug = new TestBug();
                testBug.doEvent(MainActivity.this);
            }
        });

    }

    /**
     * 查看当前activity用到的classLoader
     * */
    private void findClassLoader(){
        ClassLoader classLoader = getClassLoader();
        if (classLoader != null) {
            Log.e(TAG, "classLoader = " + classLoader);
            while (classLoader.getParent() != null) {
                classLoader = classLoader.getParent();
                Log.e(TAG, "classLoader = " + classLoader);
            }
        }
    }
}

