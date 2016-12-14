//
// NodeConsoleFragment.java
// The NodeDroid project
//
// https://github.com/ericwlange/nodedroid/
//
// Created by Eric Lange
//
/*
 Copyright (c) 2016 Eric Lange. All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions are met:

 - Redistributions of source code must retain the above copyright notice, this
 list of conditions and the following disclaimer.

 - Redistributions in binary form must reproduce the above copyright notice,
 this list of conditions and the following disclaimer in the documentation
 and/or other materials provided with the distribution.

 THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package org.liquidplayer.demoapp;

import android.app.Fragment;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.widget.NestedScrollView;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import org.liquidplayer.node.Process;
import org.liquidplayer.javascript.JSContext;
import org.liquidplayer.javascript.JSException;
import org.liquidplayer.javascript.JSFunction;
import org.liquidplayer.javascript.JSObject;
import org.liquidplayer.javascript.JSValue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.UUID;

public class NodeConsoleFragment extends Fragment
        implements Process.EventListener, JSContext.IJSExceptionHandler {

    public interface Listener {
        public void onJSReady(JSContext ctx);
    }

    private TextView textView;
    private EditText inputBox;
    private NestedScrollView scrollView;
    private ImageButton upHistory;
    private ImageButton downHistory;
    private String uuid = null;
    private Process process = null;
    private JSContext js = null;
    private JSFunction console_log = null;
    private ArrayList<String> history = new ArrayList<>();
    private int item = 0;
    private ConsoleTextView consoleTextView;
    private int rows;
    private int columns;

    public static NodeConsoleFragment newInstance(String text) {

        NodeConsoleFragment f = new NodeConsoleFragment();

        Bundle b = new Bundle();
        b.putString("text", text);
        f.setArguments(b);
        return f;
    }

    private Listener listener;
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putCharSequence("textView", textView.getText());
        outState.putStringArrayList("history", history);
        outState.putInt("item", item);
        outState.putCharSequence("inputBox", inputBox.getText());

        if (process != null) {
            outState.putString("uuid", uuid);
            process.removeEventListener(this);
        } else {
            outState.remove("uuid");
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        final View view = inflater.inflate(R.layout.activity_scrolling, container, false);

        textView = (TextView) view.findViewById(R.id.console_text);
        inputBox = (EditText) view.findViewById(R.id.inputBox);
        scrollView = (NestedScrollView) view.findViewById(R.id.scroll);

        inputBox.setFocusableInTouchMode(true);
        inputBox.requestFocus();
        inputBox.setOnEditorActionListener(new EditText.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    enterCommand();
                    return true;
                }
                return false;
            }
        });
        inputBox.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View view, int keyCode, KeyEvent event) {
                if ((event.getAction() == KeyEvent.ACTION_DOWN) &&
                        (keyCode == KeyEvent.KEYCODE_ENTER)) {
                    enterCommand();
                    return true;
                }
                if ((event.getAction() == KeyEvent.ACTION_DOWN) &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_UP)) {
                    upHistory();
                    return true;
                }
                if ((event.getAction() == KeyEvent.ACTION_DOWN) &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                    downHistory();
                    return true;
                }
                return false;
            }
        });
        inputBox.setEnabled(false);

        upHistory = (ImageButton) view.findViewById(R.id.up_history);
        downHistory = (ImageButton) view.findViewById(R.id.down_history);

        upHistory.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                upHistory();
            }
        });
        downHistory.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                downHistory();
            }
        });
        setButtonEnabled(upHistory,false);
        setButtonEnabled(downHistory,false);

        if (savedInstanceState == null) {
            uuid = UUID.randomUUID().toString();
        } else {
            textView.setText(savedInstanceState.getCharSequence("textView"));
            history = savedInstanceState.getStringArrayList("history");
            item = savedInstanceState.getInt("item");
            if (savedInstanceState.containsKey("uuid")) {
                uuid = savedInstanceState.getString("uuid");
                inputBox.setText(savedInstanceState.getCharSequence("inputBox"));
                if (item < history.size()) setButtonEnabled(downHistory,true);
                if (item > 0) setButtonEnabled(upHistory,true);
            } else {
                uuid = null;
            }
        }

        ViewTreeObserver vto = scrollView.getViewTreeObserver();
        vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                scrollView.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                Paint textPaint = textView.getPaint();
                int width = Math.round(textPaint.measureText("X") * textView.getTextScaleX());
                int height = Math.round(textPaint.getTextSize() * textView.getTextScaleX());
                rows = scrollView.getMeasuredHeight() / height;
                columns = scrollView.getMeasuredWidth() / width;

                if (uuid != null) {
                    Context context = view.getContext();
                    LocalBroadcastManager.getInstance(context).registerReceiver(
                            new BroadcastReceiver() {
                                @Override
                                public void onReceive(Context context, Intent intent) {
                                    process = NodeProcessService.getProcess(uuid);
                                    process.addEventListener(NodeConsoleFragment.this);
                                }
                            },
                            new IntentFilter(uuid));

                    Intent serviceIntent = new Intent(context, NodeProcessService.class);
                    serviceIntent.putExtra("org.liquidplayer.node.Process", uuid);
                    context.startService(serviceIntent);
                }
            }
        });

        consoleTextView = new ConsoleTextView(textView,new Runnable()
        {
            @Override
            public void run() {
                View lastChild = scrollView.getChildAt(scrollView.getChildCount() - 1);
                int bottom = lastChild.getBottom() + scrollView.getPaddingBottom();
                int sy = scrollView.getScrollY();
                int sh = scrollView.getHeight();
                int delta = bottom - (sy + sh);

                scrollView.smoothScrollBy(0, delta);
            }
        },
        new ByteArrayOutputStream());

        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                View lastChild = scrollView.getChildAt(scrollView.getChildCount() - 1);
                int bottom = lastChild.getBottom() + scrollView.getPaddingBottom();
                int sy = scrollView.getScrollY();
                int sh = scrollView.getHeight();
                int delta = bottom - (sy + sh);

                scrollView.smoothScrollBy(0, delta);
            }
        });

        return view;
    }

    private void setButtonEnabled(ImageButton button, boolean enable) {
        button.setEnabled(enable);
        button.setAlpha(enable?1f : 0.5f);
        button.setClickable(enable);
    }

    private void upHistory() {
        if (upHistory.isEnabled()) {
            inputBox.setText(history.get(--item));
            if (item == 0) {
                setButtonEnabled(upHistory, false);
            }
            setButtonEnabled(downHistory, true);
            inputBox.setSelection(inputBox.getText().length());
        }
    }

    private void downHistory() {
        if (downHistory.isEnabled()) {
            if (++item >= history.size()) {
                setButtonEnabled(downHistory, false);
                inputBox.setText("");
            } else {
                inputBox.setText(history.get(item));
            }
            setButtonEnabled(upHistory, true);
            inputBox.setSelection(inputBox.getText().length());
        }
    }

    private void enterCommand() {
        processedException = false;
        final String cmd = inputBox.getText().toString();

        consoleTextView.println("\u001b[30;1m> " + cmd);
        inputBox.setText("");
        history.add(cmd);
        item = history.size();
        setButtonEnabled(upHistory,true);
        setButtonEnabled(downHistory,false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                JSValue output = js.evaluateScript(cmd);
                if (!processedException && console_log != null) {
                    console_log.call(null, output);
                }
            }
        }).start();
    }

    private interface StreamCallback {
        void callback(String string);
    }
    private void setupStream(final JSObject stream, final StreamCallback callback) {
        stream.property("write", new JSFunction(stream.getContext(), "write") {
            @SuppressWarnings("unused")
            public void write(final String string) {
                callback.callback(string);
            }
        });
        stream.property("clearScreenDown", new JSFunction(stream.getContext(),"clearScreenDown",
                "this.write('\\x1b[0J');"));
        stream.property("moveCursor", new JSFunction(stream.getContext(),"moveCursor",
                "var out = ''; c = c || 0; r = r || 0;" +
                "if (c>0) out += '\\x1b['+c+'C'; else if (c<0) out+='\\x1b['+(-c)+'D';"+
                "if (r>0) out += '\\x1b['+r+'B'; else if (r<0) out+='\\x1b['+(-r)+'A';"+
                "this.write(out);",
                "c", "r"));
        stream.property("rows", rows);
        stream.property("columns", columns);
    }

    @Override
    public void onProcessStart(Process process, JSContext context) {
        js = context;
        js.setExceptionHandler(this);
        console_log = js.property("console").toObject().property("log").toFunction();
        process.keepAlive();
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                inputBox.setEnabled(true);
            }
        });

        JSObject stdout =
                js.property("process").toObject().property("stdout").toObject();
        JSObject stderr =
                js.property("process").toObject().property("stderr").toObject();
        setupStream(stdout, new StreamCallback() {
            @Override
            public void callback(String string) {
                consoleTextView.print(string);
            }
        });
        setupStream(stderr, new StreamCallback() {
            @Override
            public void callback(String string) {
                // Make it red!
                string = "\u001b[31m" + string;
                consoleTextView.print(string);
            }
        });

        if (listener != null) listener.onJSReady(context);
    }

    @Override
    public void onProcessAboutToExit(Process process, int exitCode) {
        console_log = null;
        js = null;
        process.letDie();
        consoleTextView.println("\u001B[31mProcess about to exit with code " + exitCode);
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                inputBox.setEnabled(false);
                inputBox.setClickable(false);
                inputBox.setAlpha(0.5f);
                setButtonEnabled(downHistory,false);
                setButtonEnabled(upHistory,false);
            }
        });
    }

    @Override
    public void onProcessExit(Process process, int exitCode) {
        consoleTextView.println("\u001B[31mProcess exited with code " + exitCode);
        this.process = null;
    }

    @Override
    public void onProcessFailed(Process process, Exception error) {

    }

    boolean processedException = false;

    @Override
    public void handle(final JSException e) {
        processedException = true;
        consoleTextView.println("\u001b[31m" + e.stack());
    }
}
