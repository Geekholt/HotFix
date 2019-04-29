package com.geekholt.hotfix;

import android.content.Context;
import android.widget.Toast;

/**
 * Created by wuhaoteng on 2018/9/5.
 */

public class TestBug {

    public void doEvent(Context context){
        String toast = "Hello";
        Toast.makeText(context, toast.length(), Toast.LENGTH_SHORT).show();
    }
}
