package uk.co.nofrills.oldcrowndisplay;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.animation.ObjectAnimator;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.text.InputType;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.PlayerView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.security.SecureRandom;
import org.json.JSONObject;
import org.json.JSONArray;
import java.net.NetworkInterface;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Enumeration;


public class MainActivity extends Activity {
    private static final int NAVY=0xff0b1727, BLUE=0xff153755, RED=0xffbb2634;
    private static final long SCAN_TIME_MS=30000;
    private final Handler handler=new Handler();
    private final List<Entry> playlist=new ArrayList<>();
    private final List<String> streams=new ArrayList<>();
    private final List<Entry> localMedia=new ArrayList<>();
    private static final String PREFS="crown_display_preferences";
    private static final String MODE_LIVE="live", MODE_LOCAL="local";
    private String mode=MODE_LOCAL;
    private int streamIndex=0;
    private int retryCount=0;
    private static final long MAX_RETRY_MS=30000L;
    private final List<File> adverts=new ArrayList<>();
    private File root;
    private FrameLayout mainArea, advertArea, tickerArea;
    private LinearLayout screenLayout, bodyLayout, rightLayout;
    private TextView brandView, tickerTag;
    private int sidePercent=24, tickerPercent=10, advertSeconds=9, pictureSeconds=12, tickerSpeed=12;
    private boolean editingSettings=false;
    private PlayerView playerView;
    private ExoPlayer player;
    private ImageView mainImage, advertImage;
    private TextView mainPlaceholder, advertPlaceholder, ticker, status;
    private ObjectAnimator tickerAnim;
    private int playlistIndex=0, advertIndex=0, failureCount=0;
    private boolean running=false, started=false, videoTransition=false;
    private String currentSource="";
    private String previousTicker="";
    private RemoteServer remoteServer;
    private String remotePassword;
    private String remoteError="";

    private static final class Entry {
        final String location;
        final boolean image;
        Entry(String l,boolean i) { location=l; image=i; }
        String key() { return location; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideNavigation();
        root=new File(Environment.getExternalStorageDirectory(),"OldCrownDisplay");
        SharedPreferences prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        mode=prefs.getString("mode",MODE_LOCAL);
        streamIndex=prefs.getInt("streamIndex",0);
        sidePercent=prefs.getInt("sidePercent",24);
        tickerPercent=prefs.getInt("tickerPercent",10);
        advertSeconds=prefs.getInt("advertSeconds",9);
        pictureSeconds=prefs.getInt("pictureSeconds",12);
        tickerSpeed=prefs.getInt("tickerSpeed",12);
        buildLayout();
        if(checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE},10);
        } else startDisplay();
    }
    private void hideNavigation() {
        getWindow().getDecorView().setSystemUiVisibility(5894|1024|512|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
    @Override public void onWindowFocusChanged(boolean focus) {super.onWindowFocusChanged(focus);if(focus)hideNavigation();}

    private TextView label(String s,int sp,int color,boolean bold) {
        TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);
        t.setGravity(Gravity.CENTER);if(bold)t.setTypeface(null,Typeface.BOLD);return t;
    }
    private void buildLayout() {
        screenLayout=new LinearLayout(this);screenLayout.setOrientation(LinearLayout.VERTICAL);screenLayout.setBackgroundColor(NAVY);setContentView(screenLayout);
        bodyLayout=new LinearLayout(this);bodyLayout.setOrientation(LinearLayout.HORIZONTAL);screenLayout.addView(bodyLayout,new LinearLayout.LayoutParams(-1,0,100-tickerPercent));
        mainArea=new FrameLayout(this);mainArea.setBackgroundColor(Color.BLACK);bodyLayout.addView(mainArea,new LinearLayout.LayoutParams(0,-1,100-sidePercent));
        playerView=new PlayerView(this);playerView.setUseController(false);
        playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
        mainArea.addView(playerView,new FrameLayout.LayoutParams(-1,-1));
        mainImage=new ImageView(this);mainImage.setScaleType(ImageView.ScaleType.FIT_CENTER);mainArea.addView(mainImage,new FrameLayout.LayoutParams(-1,-1));
        mainPlaceholder=label("THE OLD CROWN\n\nMAIN VIDEO / STREAM\n\nAdd videos to main/ or URLs to streams.txt",22,Color.WHITE,true);
        mainArea.addView(mainPlaceholder,new FrameLayout.LayoutParams(-1,-1));
        status=label("",14,Color.WHITE,false);status.setBackgroundColor(0x99000000);status.setVisibility(View.GONE);
        FrameLayout.LayoutParams statusLp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.LEFT);mainArea.addView(status,statusLp);

        rightLayout=new LinearLayout(this);rightLayout.setOrientation(LinearLayout.VERTICAL);rightLayout.setBackgroundColor(BLUE);bodyLayout.addView(rightLayout,new LinearLayout.LayoutParams(0,-1,sidePercent));
        brandView=label("THE OLD CROWN",20,Color.WHITE,true);brandView.setBackgroundColor(0xff1b4c6f);rightLayout.addView(brandView,new LinearLayout.LayoutParams(-1,0,1));
        advertArea=new FrameLayout(this);rightLayout.addView(advertArea,new LinearLayout.LayoutParams(-1,0,6));
        advertImage=new ImageView(this);advertImage.setScaleType(ImageView.ScaleType.FIT_CENTER);advertArea.addView(advertImage,new FrameLayout.LayoutParams(-1,-1));
        advertPlaceholder=label("YOUR ADVERTS HERE\n\nOldCrownDisplay/ads",17,0xffe3f2ff,true);advertArea.addView(advertPlaceholder,new FrameLayout.LayoutParams(-1,-1));

        tickerArea=new FrameLayout(this);tickerArea.setBackgroundColor(RED);screenLayout.addView(tickerArea,new LinearLayout.LayoutParams(-1,0,tickerPercent));
        ticker=label("",20,Color.WHITE,true);ticker.setSingleLine(true);ticker.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);
        tickerArea.addView(ticker,new FrameLayout.LayoutParams(-2,-1));
        tickerTag=label("  OLD CROWN  ",18,Color.WHITE,true);tickerTag.setBackgroundColor(0xff841520);
        tickerArea.addView(tickerTag,new FrameLayout.LayoutParams(-2,-1,Gravity.LEFT));
        tickerArea.post(()->refreshTicker(true));
    }
    @Override public void onRequestPermissionsResult(int code,String[] perms,int[] grants) {
        super.onRequestPermissionsResult(code,perms,grants);
        if(code==10) {
            if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)startDisplay();
            else new AlertDialog.Builder(this).setMessage("Storage permission is required to read the local folders. Grant it in Android settings, then restart.").setPositiveButton("OK",null).show();
        }
    }
    private void startDisplay() {
        if(started)return;started=true;running=true;
        new File(root,"main").mkdirs();new File(root,"ads").mkdirs();
        ensureFile("ticker.txt","KARAOKE EVERY FRIDAY • CHECK OUT OUR DRINKS OFFERS • ASK AT THE BAR FOR UPCOMING EVENTS • ");
        ensureFile("streams.txt","# One authorised direct stream URL per line.\n# HLS example: https://example.org/live/channel.m3u8\n# Lines starting with # are ignored.\n");
        player=new ExoPlayer.Builder(this).build();playerView.setPlayer(player);
        startRemoteServer();
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if(state==Player.STATE_READY) retryCount=0;
                if(state==Player.STATE_ENDED&&!videoTransition) {
                    videoTransition=true;
                    if(MODE_LIVE.equals(mode)) scheduleLiveRetry("Stream ended");
                    else handler.post(MainActivity.this::nextMain);
                }
            }
            @Override public void onPlayerError(PlaybackException error) {
                if(!videoTransition) {
                    videoTransition=true;
                    if(MODE_LIVE.equals(mode)) scheduleLiveRetry("Stream unavailable");
                    else {showStatus("File unavailable — skipping",3000);handler.postDelayed(MainActivity.this::nextMain,1200);}
                }
            }
        });
        reloadFiles();nextMain();nextAdvert();refreshTicker(true);
        handler.postDelayed(advertLoop,advertSeconds*1000L);handler.postDelayed(scanLoop,SCAN_TIME_MS);
    }
    private void ensureFile(String name,String text) {
        File f=new File(root,name);if(f.exists())return;
        try(FileOutputStream out=new FileOutputStream(f)){out.write(text.getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}
    }
    private boolean isImage(String name) {String n=name.toLowerCase(Locale.US);return n.endsWith(".jpg")||n.endsWith(".jpeg")||n.endsWith(".png")||n.endsWith(".webp");}
    private boolean isVideo(String name) {String n=name.toLowerCase(Locale.US);return n.endsWith(".mp4")||n.endsWith(".mkv")||n.endsWith(".webm")||n.endsWith(".3gp")||n.endsWith(".m4v")||n.endsWith(".ts");}
    private List<File> files(String folder,boolean acceptVideo) {
        File[] found=new File(root,folder).listFiles();List<File> out=new ArrayList<>();if(found==null)return out;
        for(File f:found)if(f.isFile()&&(isImage(f.getName())||(acceptVideo&&isVideo(f.getName()))))out.add(f);
        Collections.sort(out,Comparator.comparing(File::getName,String.CASE_INSENSITIVE_ORDER));return out;
    }
    private String readFile(File f) throws Exception {
        try(FileInputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private void reloadFiles() {
        List<Entry> updated=new ArrayList<>();
        for(File f:files("main",true))updated.add(new Entry(Uri.fromFile(f).toString(),isImage(f.getName())));
        List<String> updatedStreams=new ArrayList<>();
        try {
            String[] lines=readFile(new File(root,"streams.txt")).split("\r?\n");
            for(String raw:lines) {
                String url=raw.trim();if(url.isEmpty()||url.startsWith("#"))continue;
                Uri parsed=Uri.parse(url);String scheme=parsed.getScheme();
                if(("http".equalsIgnoreCase(scheme)||"https".equalsIgnoreCase(scheme))&&!url.contains(" "))updatedStreams.add(url);
            }
        } catch(Exception ignored){}
        localMedia.clear();localMedia.addAll(updated);
        streams.clear();streams.addAll(updatedStreams);
        playlist.clear();playlist.addAll(localMedia);
        adverts.clear();adverts.addAll(files("ads",false));
    }
    private void nextMain() {
        handler.removeCallbacks(imageAdvance);
        handler.removeCallbacks(liveRetry);
        videoTransition=false;
        if(!running||player==null)return;
        player.stop();player.clearMediaItems();
        mainImage.setImageDrawable(null);
        if(MODE_LIVE.equals(mode)) {
            playSelectedStream();
            return;
        }
        if(playlist.isEmpty()) {
            currentSource="";
            mainPlaceholder.setText("LOCAL MEDIA MODE\n\nAdd images or videos to\nOldCrownDisplay/main/");
            mainPlaceholder.setVisibility(View.VISIBLE);mainImage.setVisibility(View.GONE);playerView.setVisibility(View.GONE);
            return;
        }
        Entry e=playlist.get(playlistIndex++ % playlist.size());
        currentSource=e.location;
        mainPlaceholder.setVisibility(View.GONE);
        if(e.image) {
            playerView.setVisibility(View.GONE);mainImage.setVisibility(View.VISIBLE);
            String path=Uri.parse(e.location).getPath();
            mainImage.setImageBitmap(BitmapFactory.decodeFile(path));
            handler.postDelayed(imageAdvance,pictureSeconds*1000L);
        } else {
            mainImage.setVisibility(View.GONE);playerView.setVisibility(View.VISIBLE);
            try {
                player.setRepeatMode(Player.REPEAT_MODE_OFF);
                player.setMediaItem(MediaItem.fromUri(Uri.parse(e.location)));
                player.prepare();player.play();
            } catch(Exception ex) {handler.postDelayed(this::nextMain,1200);}
        }
    }
    private void playSelectedStream() {
        if(streams.isEmpty()) {
            currentSource="";
            mainPlaceholder.setText("LIVE STREAM MODE\n\nAdd a direct streaming URL to\nOldCrownDisplay/streams.txt\nthen press OK > Refresh");
            mainPlaceholder.setVisibility(View.VISIBLE);mainImage.setVisibility(View.GONE);playerView.setVisibility(View.GONE);
            return;
        }
        streamIndex=Math.max(0,Math.min(streamIndex,streams.size()-1));
        String url=streams.get(streamIndex);
        currentSource=url;
        mainPlaceholder.setVisibility(View.GONE);mainImage.setVisibility(View.GONE);playerView.setVisibility(View.VISIBLE);
        try {
            player.setRepeatMode(Player.REPEAT_MODE_OFF);
            player.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
            player.prepare();player.play();
        } catch(Exception ex) {videoTransition=true;scheduleLiveRetry("Stream could not start");}
    }
    private long retryDelay() {return Math.min(MAX_RETRY_MS,2000L*(1L<<Math.min(retryCount++,4)));}
    private void scheduleLiveRetry(String reason) {
        if(!running||!MODE_LIVE.equals(mode))return;
        long delay=retryDelay();
        showStatus(reason+" — retrying in "+(delay/1000)+"s",Math.min(delay,4000));
        handler.removeCallbacks(liveRetry);
        handler.postDelayed(liveRetry,delay);
    }
    private final Runnable liveRetry=()->{
        if(running&&MODE_LIVE.equals(mode))nextMain();
    };
    private void setMode(String value) {
        mode=value;
        retryCount=0;playlistIndex=0;
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString("mode",mode).apply();
        nextMain();
        showStatus(MODE_LIVE.equals(mode)?"Live stream mode":"Local playlist mode",2500);
    }
    private void chooseStream() {
        reloadFiles();
        if(streams.isEmpty()) {new AlertDialog.Builder(this).setTitle("No streams configured")
            .setMessage("Add one direct streaming URL per line to /sdcard/OldCrownDisplay/streams.txt, then refresh.")
            .setPositiveButton("OK",null).show();return;}
        String[] labels=new String[streams.size()];
        for(int i=0;i<streams.size();i++)labels[i]=(i+1)+". "+streams.get(i);
        new AlertDialog.Builder(this).setTitle("Choose live stream")
            .setSingleChoiceItems(labels,Math.min(streamIndex,streams.size()-1),(dialog,which)->{
                streamIndex=which;getSharedPreferences(PREFS,MODE_PRIVATE).edit().putInt("streamIndex",streamIndex).apply();
                dialog.dismiss();setMode(MODE_LIVE);
            }).setNegativeButton("Cancel",null).show();
    }
    private final Runnable imageAdvance=this::nextMain;
    private void nextAdvert() {
        if(adverts.isEmpty()) {advertImage.setVisibility(View.GONE);advertPlaceholder.setVisibility(View.VISIBLE);return;}
        File f=adverts.get((advertIndex++ % adverts.size()));
        advertImage.setVisibility(View.VISIBLE);advertPlaceholder.setVisibility(View.GONE);
        advertImage.setImageBitmap(BitmapFactory.decodeFile(f.getAbsolutePath()));
        advertImage.setAlpha(.2f);advertImage.animate().alpha(1f).setDuration(450).start();
    }
    private final Runnable advertLoop=new Runnable() { public void run() {if(!running)return;nextAdvert();handler.postDelayed(this,advertSeconds*1000L);} };
    private final Runnable scanLoop=new Runnable() { public void run() {
        if(!running)return;
        boolean empty=MODE_LIVE.equals(mode)?streams.isEmpty():playlist.isEmpty(),adsEmpty=adverts.isEmpty();
        reloadFiles();
        if(empty&&!(MODE_LIVE.equals(mode)?streams.isEmpty():playlist.isEmpty()))nextMain();
        if(adsEmpty&&!adverts.isEmpty())nextAdvert();
        refreshTicker(false);handler.postDelayed(this,SCAN_TIME_MS);
    }};
    private void refreshTicker(boolean force) {
        String message="KARAOKE • DRINKS OFFERS • WHAT'S ON AT THE OLD CROWN • ";
        try {String s=readFile(new File(root,"ticker.txt")).trim();if(!s.isEmpty())message=s.replace('\n',' ').replace('\r',' ');}catch(Exception ignored){}
        if(!force&&message.equals(previousTicker))return;
        previousTicker=message;
        if(tickerAnim!=null)tickerAnim.cancel();ticker.setText(message+"     ✦     "+message+"     ✦     ");
        ticker.post(()->{
            if(tickerAnim!=null)tickerAnim.cancel();int w=tickerArea.getWidth();
            ticker.measure(View.MeasureSpec.UNSPECIFIED,View.MeasureSpec.UNSPECIFIED);
            tickerAnim=ObjectAnimator.ofFloat(ticker,"translationX",w,-ticker.getMeasuredWidth());
            tickerAnim.setInterpolator(new LinearInterpolator());
            tickerAnim.setDuration(Math.max(10000,(w+ticker.getMeasuredWidth())*(long)tickerSpeed));
            tickerAnim.setRepeatCount(ObjectAnimator.INFINITE);tickerAnim.start();
        });
    }
    private void showStatus(String s,long ms) {
        status.setText("  "+s+"  ");status.setVisibility(View.VISIBLE);
        handler.postDelayed(()->status.setVisibility(View.GONE),ms);
    }
    private void refreshNow() {
        reloadFiles();playlistIndex=0;advertIndex=0;retryCount=0;nextMain();nextAdvert();refreshTicker(true);showStatus("Content refreshed",2000);
    }
    private int dp(int n) {return (int)(getResources().getDisplayMetrics().density*n+0.5f);}
    private void applyLayout() {
        screenLayout.setWeightSum(100);
        bodyLayout.setLayoutParams(new LinearLayout.LayoutParams(-1,0,100-tickerPercent));
        tickerArea.setLayoutParams(new LinearLayout.LayoutParams(-1,0,tickerPercent));
        bodyLayout.setWeightSum(100);
        mainArea.setLayoutParams(new LinearLayout.LayoutParams(0,-1,100-sidePercent));
        rightLayout.setLayoutParams(new LinearLayout.LayoutParams(0,-1,sidePercent));
        // Recalculate scrolling text start/end when ticker dimensions change.
        tickerArea.post(()->refreshTicker(true));
    }
    private SeekBar addSetting(LinearLayout parent,String name,int min,int max,int initial) {
        TextView title=label(name+" — "+initial,16,Color.WHITE,true);
        title.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(33));p.topMargin=dp(10);
        parent.addView(title,p);
        SeekBar seek=new SeekBar(this);seek.setMax(max-min);seek.setProgress(initial-min);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int v,boolean user){title.setText(name+" — "+(v+min));}
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){}
        });
        parent.addView(seek,new LinearLayout.LayoutParams(-1,dp(45)));
        return seek;
    }
    private EditText addTextField(LinearLayout parent,String title,String value,boolean multiline) {
        TextView name=label(title,16,Color.WHITE,true);name.setGravity(Gravity.LEFT);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(30));p.topMargin=dp(12);parent.addView(name,p);
        EditText editor=new EditText(this);
        editor.setTextColor(Color.WHITE);editor.setHintTextColor(0xffbbbbbb);editor.setBackgroundColor(0xff243c53);
        editor.setTextSize(15);editor.setPadding(dp(10),dp(6),dp(10),dp(6));editor.setText(value);
        editor.setInputType(InputType.TYPE_CLASS_TEXT|(multiline?InputType.TYPE_TEXT_FLAG_MULTI_LINE:0));
        editor.setMinLines(multiline?3:1);editor.setMaxLines(multiline?5:1);
        parent.addView(editor,new LinearLayout.LayoutParams(-1,multiline?dp(95):dp(46)));
        return editor;
    }
    private void writeFile(String name,String contents) throws Exception {
        try(FileOutputStream out=new FileOutputStream(new File(root,name))){out.write(contents.getBytes(StandardCharsets.UTF_8));}
    }
    private void showControlCentre() {
        if(editingSettings)return;
        editingSettings=true;
        // All controls are native Android views, operable with a TV D-pad and OK button.
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(NAVY);panel.setPadding(dp(20),dp(12),dp(20),dp(20));scroll.addView(panel);
        TextView explanation=label("Adjust the display, playback timers and messages. Changes are saved when you select SAVE.",15,0xffd4e7f5,false);
        explanation.setGravity(Gravity.LEFT);panel.addView(explanation);
        SeekBar side=addSetting(panel,"Right panel width (%)",15,45,sidePercent);
        SeekBar tickerHeight=addSetting(panel,"Bottom ticker height (%)",6,25,tickerPercent);
        SeekBar adTime=addSetting(panel,"Advert duration (seconds)",3,60,advertSeconds);
        SeekBar imageTime=addSetting(panel,"Main photo duration (seconds)",3,60,pictureSeconds);
        SeekBar speed=addSetting(panel,"Ticker speed (ms per pixel)",4,25,tickerSpeed);
        String oldTicker="";String oldStreams="";
        try {oldTicker=readFile(new File(root,"ticker.txt"));}catch(Exception ignored){}
        try {oldStreams=readFile(new File(root,"streams.txt"));}catch(Exception ignored){}
        EditText tickerEditor=addTextField(panel,"TICKER MESSAGE",oldTicker,true);
        EditText streamEditor=addTextField(panel,"STREAM URLS — one URL per line",oldStreams,true);
        TextView folderHint=label("To replace posters or local media, copy files into /sdcard/OldCrownDisplay/ads/ or /main/, then select Refresh in the menu.",14,0xffa9c8dd,false);
        folderHint.setGravity(Gravity.LEFT);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(14);panel.addView(folderHint,lp);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("OLD CROWN — CONTROL CENTRE")
            .setView(scroll).setPositiveButton("SAVE",null).setNegativeButton("CANCEL",(d,w)->editingSettings=false).create();
        dialog.setOnDismissListener(d->editingSettings=false);
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try {
                int newSide=side.getProgress()+15, newTicker=tickerHeight.getProgress()+6;
                int newAd=adTime.getProgress()+3,newImage=imageTime.getProgress()+3,newSpeed=speed.getProgress()+4;
                String newStreams=streamEditor.getText().toString();
                for(String line:newStreams.split("\\r?\\n")) {
                    String item=line.trim();if(item.isEmpty()||item.startsWith("#"))continue;
                    Uri u=Uri.parse(item);String scheme=u.getScheme();
                    if(!"http".equalsIgnoreCase(scheme)&&!"https".equalsIgnoreCase(scheme))throw new IllegalArgumentException("Stream links must start with http:// or https://");
                }
                writeFile("ticker.txt",tickerEditor.getText().toString().trim());
                writeFile("streams.txt",newStreams);
                sidePercent=newSide;tickerPercent=newTicker;advertSeconds=newAd;pictureSeconds=newImage;tickerSpeed=newSpeed;
                getSharedPreferences(PREFS,MODE_PRIVATE).edit()
                    .putInt("sidePercent",sidePercent).putInt("tickerPercent",tickerPercent)
                    .putInt("advertSeconds",advertSeconds).putInt("pictureSeconds",pictureSeconds)
                    .putInt("tickerSpeed",tickerSpeed).apply();
                applyLayout();handler.removeCallbacks(advertLoop);handler.postDelayed(advertLoop,advertSeconds*1000L);
                refreshNow();dialog.dismiss();
            }catch(Exception ex){new AlertDialog.Builder(this).setTitle("Could not save")
                .setMessage(ex.getMessage()).setPositiveButton("OK",null).show();}
        }));
        dialog.show();
    }
    private void showRemoteMenu() {
        String[] options={"CONTROL CENTRE — Settings & layout", "Play live stream", "Play local media playlist", "Choose live stream", "Refresh media and adverts", "NETWORK REMOTE — Address & password", "Show folder instructions"};
        new AlertDialog.Builder(this).setTitle("Old Crown Display v0.5 — "+(MODE_LIVE.equals(mode)?"LIVE":"LOCAL"))
          .setItems(options,(dialog,choice)->{
              switch(choice) {
                  case 0: showControlCentre();break;
                  case 1: setMode(MODE_LIVE);break;
                  case 2: setMode(MODE_LOCAL);break;
                  case 3: chooseStream();break;
                  case 4: refreshNow();break;
                  case 5: showRemoteDetails();break;
                  case 6: new AlertDialog.Builder(this).setTitle("Content folders")
                      .setMessage("/sdcard/OldCrownDisplay/main/ — local videos and pictures, played in filename order and looped\n\n/sdcard/OldCrownDisplay/ads/ — rotating posters\n\n/sdcard/OldCrownDisplay/streams.txt — authorised direct HLS, DASH or HTTP video URL per line\n\n/sdcard/OldCrownDisplay/ticker.txt — bottom ticker text\n\nPress OK or MENU to change playback mode. Live mode plays only the selected stream and retries on failure.")
                      .setPositiveButton("Close",null).show();break;
              }
          }).setNegativeButton("Close",null).show();
    }

    private String createPassword() {
        byte[] bytes=new byte[9];new SecureRandom().nextBytes(bytes);
        StringBuilder out=new StringBuilder();
        for(byte b:bytes)out.append(String.format(Locale.US,"%02x",b&255));
        return out.toString();
    }
    private void startRemoteServer() {
        SharedPreferences prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        remotePassword=prefs.getString("remotePassword","");
        if(remotePassword.isEmpty()) {
            remotePassword=createPassword();
            prefs.edit().putString("remotePassword",remotePassword).apply();
        }
        try {
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            try(java.io.InputStream in=getAssets().open("remote.html")) {
                byte[] tmp=new byte[8192];int n;
                while((n=in.read(tmp))!=-1)buffer.write(tmp,0,n);
            }
            remoteServer=new RemoteServer(8765,remotePassword,new RemoteServer.Controller(){
                @Override public JSONObject state() throws Exception {return onUi(()->remoteState());}
                @Override public JSONObject command(Map<String,String> fields) throws Exception {return onUi(()->remoteCommand(fields));}
                @Override public JSONObject upload(String folder,String filename,byte[] content) throws Exception {return remoteUpload(folder,filename,content);}
                @Override public JSONObject delete(String folder,String filename) throws Exception {return remoteDelete(folder,filename);}
            },buffer.toByteArray());
            remoteServer.start();remoteError="";
        }catch(Exception e){remoteError=e.toString();}
    }
    private interface UiJob {JSONObject run() throws Exception;}
    private JSONObject onUi(UiJob job) throws Exception {
        FutureTask<JSONObject> task=new FutureTask<>(job::run);
        runOnUiThread(task);
        return task.get(10,TimeUnit.SECONDS);
    }
    private JSONObject success(String message)throws Exception {return new JSONObject().put("message",message);}
    private JSONObject remoteState()throws Exception {
        JSONObject state=new JSONObject();
        state.put("mode",mode).put("streamIndex",streamIndex).put("currentSource",currentSource)
             .put("sidePercent",sidePercent).put("tickerPercent",tickerPercent)
             .put("advertSeconds",advertSeconds).put("pictureSeconds",pictureSeconds)
             .put("tickerSpeed",tickerSpeed);
        state.put("streams",new JSONArray(streams));
        String message="";try{message=readFile(new File(root,"ticker.txt"));}catch(Exception ignored){}
        state.put("ticker",message);
        JSONArray ads=new JSONArray(),main=new JSONArray();
        for(File f:files("ads",false))ads.put(f.getName());
        for(File f:files("main",true))main.put(f.getName());
        state.put("ads",ads).put("main",main);
        return state;
    }
    private int setting(Map<String,String> p,String key,int min,int max) {
        int n=Integer.parseInt(p.get(key));if(n<min||n>max)throw new IllegalArgumentException("Invalid "+key);return n;
    }
    private void validateStreams(String lines) {
        for(String line:lines.split("\\r?\\n")) {
            String item=line.trim();if(item.isEmpty()||item.startsWith("#"))continue;
            Uri uri=Uri.parse(item);String scheme=uri.getScheme();
            if((!"http".equalsIgnoreCase(scheme)&&!"https".equalsIgnoreCase(scheme))||item.contains(" "))
                throw new IllegalArgumentException("Only direct HTTP/HTTPS stream URLs allowed");
        }
    }
    private JSONObject remoteCommand(Map<String,String> p)throws Exception {
        try {
            String action=p.get("action");if(action==null)throw new IllegalArgumentException("Missing action");
            switch(action) {
                case "mode":
                    if(!MODE_LOCAL.equals(p.get("value"))&&!MODE_LIVE.equals(p.get("value")))throw new IllegalArgumentException("Invalid mode");
                    setMode(p.get("value"));break;
                case "stream":
                    int i=Integer.parseInt(p.get("value"));if(i<0||i>=streams.size())throw new IllegalArgumentException("Stream not found");
                    streamIndex=i;getSharedPreferences(PREFS,MODE_PRIVATE).edit().putInt("streamIndex",i).apply();
                    setMode(MODE_LIVE);break;
                case "ticker":
                    if(p.get("value")==null||p.get("value").length()>4000)throw new IllegalArgumentException("Ticker too long");
                    writeFile("ticker.txt",p.get("value"));refreshTicker(true);break;
                case "streams":
                    String lines=p.get("value");if(lines==null||lines.length()>12000)throw new IllegalArgumentException("Stream list too long");
                    validateStreams(lines);writeFile("streams.txt",lines);refreshNow();break;
                case "settings":
                    int side=setting(p,"sidePercent",15,45),tick=setting(p,"tickerPercent",6,25);
                    int ad=setting(p,"advertSeconds",3,60),pic=setting(p,"pictureSeconds",3,60),speed=setting(p,"tickerSpeed",4,25);
                    sidePercent=side;tickerPercent=tick;advertSeconds=ad;pictureSeconds=pic;tickerSpeed=speed;
                    getSharedPreferences(PREFS,MODE_PRIVATE).edit().putInt("sidePercent",side).putInt("tickerPercent",tick)
                        .putInt("advertSeconds",ad).putInt("pictureSeconds",pic).putInt("tickerSpeed",speed).apply();
                    applyLayout();handler.removeCallbacks(advertLoop);handler.postDelayed(advertLoop,ad*1000L);break;
                case "refresh":refreshNow();break;
                default:throw new IllegalArgumentException("Unknown command");
            }
            return success("Change applied to TV");
        }catch(Exception e){return new JSONObject().put("error",e.getMessage()==null?"Invalid request":e.getMessage());}
    }
    private File checkedFile(String folder,String filename) throws Exception {
        if(!"ads".equals(folder)&&!"main".equals(folder))throw new IllegalArgumentException("Invalid folder");
        if(filename==null||filename.isEmpty()||filename.length()>128||filename.equals(".")||filename.equals("..")||filename.contains("/")||filename.contains("\\")||filename.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid filename");
        if(!isImage(filename)&&!("main".equals(folder)&&isVideo(filename)))throw new IllegalArgumentException("Unsupported media format");
        File directory=new File(root,folder);directory.mkdirs();
        return new File(directory,filename);
    }
    private JSONObject remoteUpload(String folder,String name,byte[] content)throws Exception {
        try {
            File file=checkedFile(folder,name);
            if(content.length==0)throw new IllegalArgumentException("Empty file");
            File temp=new File(file.getParentFile(),".incoming_"+System.nanoTime());
            try(FileOutputStream out=new FileOutputStream(temp)){out.write(content);}
            if(file.exists()&&!file.delete())throw new IOException("Could not replace file");
            if(!temp.renameTo(file)){temp.delete();throw new IOException("Could not save uploaded file");}
            runOnUiThread(()->{reloadFiles();nextAdvert();});
            return success("Uploaded "+name);
        }catch(Exception e){return new JSONObject().put("error",e.getMessage());}
    }
    private JSONObject remoteDelete(String folder,String name)throws Exception {
        try {
            File file=checkedFile(folder,name);
            if(!file.exists()||!file.delete())throw new IOException("Unable to delete file");
            runOnUiThread(()->refreshNow());
            return success("Deleted "+name);
        }catch(Exception e){return new JSONObject().put("error",e.getMessage());}
    }
    private String localIpAddress() {
        try {
            Enumeration<NetworkInterface> list=NetworkInterface.getNetworkInterfaces();
            while(list.hasMoreElements()) {
                NetworkInterface net=list.nextElement();if(!net.isUp()||net.isLoopback())continue;
                Enumeration<InetAddress> addresses=net.getInetAddresses();
                while(addresses.hasMoreElements()) {
                    InetAddress a=addresses.nextElement();
                    if(a instanceof Inet4Address&&a.isSiteLocalAddress())return a.getHostAddress();
                }
            }
        }catch(Exception ignored){}
        return "(check Android Wi-Fi settings for the IP address)";
    }
    private void showRemoteDetails() {
        String address=localIpAddress();
        String message="From your Windows laptop or phone on the same trusted network, open:\n\nhttp://"+address+":8765/\n\nUsername: admin\nPassword: "+remotePassword+
          "\n\nKeep this password private. Do not forward port 8765 on your router.\n\n"+
          (remoteError.isEmpty()?"Remote server started.":"Remote server error: "+remoteError);
        new AlertDialog.Builder(this).setTitle("NETWORK REMOTE ACCESS").setMessage(message)
            .setPositiveButton("Close",null).show();
    }

    @Override public boolean onKeyDown(int code,KeyEvent event) {
        if(code==KeyEvent.KEYCODE_MENU||code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER) {
            showRemoteMenu();return true;
        }
        return super.onKeyDown(code,event);
    }
    @Override protected void onDestroy() {
        running=false;handler.removeCallbacksAndMessages(null);
        if(tickerAnim!=null)tickerAnim.cancel();
        if(remoteServer!=null)remoteServer.stop();
        if(player!=null){player.release();player=null;}
        super.onDestroy();
    }
}
