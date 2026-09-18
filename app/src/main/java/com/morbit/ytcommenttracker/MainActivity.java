package com.morbit.ytcommenttracker;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    static final String PREFS="ytct", KEY_API="api", KEY_ITEMS="items";
    final ExecutorService pool=Executors.newSingleThreadExecutor();
    final Handler ui=new Handler(Looper.getMainLooper());
    final ArrayList<Item> items=new ArrayList<>();
    SharedPreferences prefs;
    LinearLayout feed;
    TextView status;
    ProgressBar progress;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        buildUi();
        load();
        render();
        handleShare(getIntent());
    }

    @Override protected void onNewIntent(Intent i){
        super.onNewIntent(i);
        setIntent(i);
        handleShare(i);
    }

    void buildUi(){
        getWindow().setStatusBarColor(Color.rgb(10,10,10));
        getWindow().setNavigationBarColor(Color.rgb(10,10,10));

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(10,10,10));
        root.setPadding(dp(12),dp(12),dp(12),dp(12));

        LinearLayout bar=new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title=new TextView(this);
        title.setText("YT Comment Tracker");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(null,1);
        bar.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));

        Button add=button("+");
        add.setContentDescription("Добавить");
        add.setOnClickListener(v->showAdd(""));
        bar.addView(add,new LinearLayout.LayoutParams(dp(52),dp(44)));

        Button refresh=button("↻");
        refresh.setOnClickListener(v->refreshAll());
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(dp(52),dp(44));
        rp.setMargins(dp(8),0,0,0);
        bar.addView(refresh,rp);

        Button settings=button("⚙");
        settings.setOnClickListener(v->showSettings());
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(52),dp(44));
        sp.setMargins(dp(8),0,0,0);
        bar.addView(settings,sp);

        root.addView(bar);

        LinearLayout srow=new LinearLayout(this);
        srow.setGravity(Gravity.CENTER_VERTICAL);
        progress=new ProgressBar(this);
        progress.setVisibility(View.GONE);
        srow.addView(progress,new LinearLayout.LayoutParams(dp(22),dp(22)));
        status=new TextView(this);
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(12);
        LinearLayout.LayoutParams stp=new LinearLayout.LayoutParams(0,dp(34),1);
        stp.setMargins(dp(8),0,0,0);
        srow.addView(status,stp);
        root.addView(srow);

        ScrollView scroll=new ScrollView(this);
        feed=new LinearLayout(this);
        feed.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(feed,new ScrollView.LayoutParams(-1,-2));
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }

    Button button(String text){
        Button b=new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        return b;
    }

    void render(){
        feed.removeAllViews();
        if(items.isEmpty()){
            TextView empty=new TextView(this);
            empty.setText("Пока пусто. Нажми + и вставь ссылку на свой комментарий YouTube.");
            empty.setTextColor(Color.GRAY);
            empty.setTextSize(16);
            empty.setPadding(dp(8),dp(40),dp(8),dp(8));
            feed.addView(empty);
        }
        for(Item it:items) feed.addView(card(it));
        updateStatus();
    }

    View card(Item it){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14),dp(14),dp(14),dp(14));
        box.setBackgroundColor(Color.rgb(28,28,28));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);
        bp.setMargins(0,0,0,dp(10));
        box.setLayoutParams(bp);

        TextView meta=new TextView(this);
        meta.setText((it.videoTitle.length()>0?it.videoTitle:"YouTube")+"\n"+it.author);
        meta.setTextColor(Color.rgb(180,180,180));
        meta.setTextSize(13);
        box.addView(meta);

        TextView text=new TextView(this);
        text.setText(it.text.length()>0?it.text:it.url);
        text.setTextColor(Color.WHITE);
        text.setTextSize(16);
        text.setPadding(0,dp(8),0,dp(8));
        box.addView(text);

        int newLikes=Math.max(0,it.likes-it.seenLikes);
        int newReplies=0;
        for(Reply r:it.replies) if(!it.seenReplyIds.contains(r.id)) newReplies++;

        TextView counters=new TextView(this);
        counters.setText("👍 "+it.likes+(newLikes>0?"  (+"+newLikes+")":"")+"   💬 "+it.replies.size()+(newReplies>0?"  (+"+newReplies+")":""));
        counters.setTextColor((newLikes+newReplies)>0?Color.rgb(255,220,90):Color.LTGRAY);
        counters.setTextSize(14);
        box.addView(counters);

        if(it.error.length()>0){
            TextView err=new TextView(this);
            err.setText("⚠ "+it.error);
            err.setTextColor(Color.rgb(255,120,120));
            err.setPadding(0,dp(6),0,0);
            box.addView(err);
        }

        for(Reply r:it.replies){
            TextView rv=new TextView(this);
            boolean unread=!it.seenReplyIds.contains(r.id);
            rv.setText("↳ "+r.author+": "+r.text);
            rv.setTextColor(unread?Color.WHITE:Color.rgb(130,130,130));
            rv.setTextSize(14);
            rv.setPadding(dp(10),dp(7),0,0);
            box.addView(rv);
        }

        LinearLayout actions=new LinearLayout(this);
        actions.setGravity(Gravity.END);

        Button open=button("YouTube");
        open.setOnClickListener(v->{
            try{ startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(it.url))); }
            catch(Exception e){ toast("Не удалось открыть ссылку"); }
        });
        actions.addView(open);

        Button read=button("Прочитано");
        read.setOnClickListener(v->{
            it.seenLikes=it.likes;
            it.seenReplyIds.clear();
            for(Reply r:it.replies) it.seenReplyIds.add(r.id);
            save(); render();
        });
        actions.addView(read);

        Button del=button("Удалить");
        del.setOnClickListener(v->new AlertDialog.Builder(this)
                .setTitle("Удалить комментарий из трекера?")
                .setPositiveButton("Удалить",(d,w)->{items.remove(it);save();render();})
                .setNegativeButton("Отмена",null).show());
        actions.addView(del);
        box.addView(actions);
        return box;
    }

    void showSettings(){
        EditText input=new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(prefs.getString(KEY_API,""));
        input.setHint("YouTube Data API v3 key");
        new AlertDialog.Builder(this)
                .setTitle("API key")
                .setMessage("Нужен ключ Google Cloud с включённым YouTube Data API v3.")
                .setView(input)
                .setPositiveButton("Сохранить",(d,w)->{prefs.edit().putString(KEY_API,input.getText().toString().trim()).apply();updateStatus();})
                .setNegativeButton("Отмена",null).show();
    }

    void showAdd(String preset){
        EditText input=new EditText(this);
        input.setSingleLine(false);
        input.setText(preset);
        input.setHint("Вставь ссылку YouTube с параметром lc=...");
        new AlertDialog.Builder(this)
                .setTitle("Добавить комментарий")
                .setView(input)
                .setPositiveButton("Добавить",(d,w)->addUrl(input.getText().toString().trim()))
                .setNegativeButton("Отмена",null).show();
    }

    void handleShare(Intent intent){
        if(intent!=null && Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())){
            String s=intent.getStringExtra(Intent.EXTRA_TEXT);
            if(s!=null && !s.trim().isEmpty()) showAdd(extractUrl(s));
        }
    }

    String extractUrl(String s){
        if(s==null) return "";
        int a=s.indexOf("http");
        if(a<0) return s.trim();
        int b=s.indexOf(' ',a);
        return (b<0?s.substring(a):s.substring(a,b)).trim();
    }

    void addUrl(String raw){
        String url=extractUrl(raw);
        String cid=commentId(url);
        if(cid==null){ toast("В ссылке не найден параметр lc — нужна ссылка именно на комментарий."); return; }
        for(Item x:items) if(cid.equals(x.id)){ toast("Этот комментарий уже отслеживается"); return; }
        Item it=new Item(); it.id=cid; it.url=url;
        items.add(0,it); save(); render();
        refreshOne(it,true);
    }

    String commentId(String url){
        try{
            String lc=Uri.parse(url).getQueryParameter("lc");
            return lc==null||lc.isEmpty()?null:lc;
        }catch(Exception e){ return null; }
    }

    void refreshAll(){
        if(items.isEmpty()){ toast("Сначала добавь комментарий"); return; }
        String key=prefs.getString(KEY_API,"").trim();
        if(key.isEmpty()){ showSettings(); return; }
        progress.setVisibility(View.VISIBLE);
        status.setText("Обновление…");
        pool.submit(()->{
            for(Item it:new ArrayList<>(items)){
                try{ updateItem(it,key); }catch(Exception e){ it.error=friendly(e); }
            }
            save();
            ui.post(()->{progress.setVisibility(View.GONE);render();});
        });
    }

    void refreshOne(Item it, boolean markInitialSeen){
        String key=prefs.getString(KEY_API,"").trim();
        if(key.isEmpty()){ showSettings(); return; }
        progress.setVisibility(View.VISIBLE);
        pool.submit(()->{
            try{
                updateItem(it,key);
                if(markInitialSeen){
                    it.seenLikes=it.likes;
                    it.seenReplyIds.clear();
                    for(Reply r:it.replies) it.seenReplyIds.add(r.id);
                }
            }catch(Exception e){ it.error=friendly(e); }
            save();
            ui.post(()->{progress.setVisibility(View.GONE);render();});
        });
    }

    void updateItem(Item it,String key) throws Exception{
        JSONObject c=getJson("https://www.googleapis.com/youtube/v3/comments?part=snippet&id="+enc(it.id)+"&textFormat=plainText&key="+enc(key));
        JSONArray arr=c.optJSONArray("items");
        if(arr==null||arr.length()==0) throw new Exception("Комментарий не найден или недоступен");
        JSONObject sn=arr.getJSONObject(0).getJSONObject("snippet");
        it.author=sn.optString("authorDisplayName","");
        it.text=sn.optString("textDisplay","");
        it.likes=sn.optInt("likeCount",0);
        it.videoId=sn.optString("videoId","");
        it.error="";

        if(!it.videoId.isEmpty()){
            JSONObject v=getJson("https://www.googleapis.com/youtube/v3/videos?part=snippet&id="+enc(it.videoId)+"&key="+enc(key));
            JSONArray va=v.optJSONArray("items");
            if(va!=null&&va.length()>0) it.videoTitle=va.getJSONObject(0).getJSONObject("snippet").optString("title","");
        }

        ArrayList<Reply> rs=new ArrayList<>();
        String token="";
        do{
            String u="https://www.googleapis.com/youtube/v3/comments?part=snippet&parentId="+enc(it.id)+"&maxResults=100&textFormat=plainText&key="+enc(key);
            if(!token.isEmpty()) u+="&pageToken="+enc(token);
            JSONObject rj=getJson(u);
            JSONArray ra=rj.optJSONArray("items");
            if(ra!=null) for(int i=0;i<ra.length();i++){
                JSONObject ro=ra.getJSONObject(i);
                JSONObject s=ro.getJSONObject("snippet");
                Reply r=new Reply();
                r.id=ro.optString("id","");
                r.author=s.optString("authorDisplayName","");
                r.text=s.optString("textDisplay","");
                r.published=s.optString("publishedAt","");
                rs.add(r);
            }
            token=rj.optString("nextPageToken","");
        }while(!token.isEmpty() && rs.size()<500);
        Collections.sort(rs,(a,b)->a.published.compareTo(b.published));
        it.replies.clear(); it.replies.addAll(rs);
    }

    JSONObject getJson(String u) throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(15000); c.setRequestMethod("GET");
        int code=c.getResponseCode();
        InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();
        String body=readAll(in); c.disconnect();
        if(code<200||code>=300){
            try{
                JSONObject e=new JSONObject(body).optJSONObject("error");
                if(e!=null) throw new Exception(e.optString("message","HTTP "+code));
            }catch(JSONException ignored){}
            throw new Exception("HTTP "+code);
        }
        return new JSONObject(body);
    }

    String readAll(InputStream in) throws Exception{
        if(in==null) return "";
        BufferedReader r=new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder b=new StringBuilder(); String line;
        while((line=r.readLine())!=null) b.append(line);
        r.close(); return b.toString();
    }

    String enc(String s){ return Uri.encode(s==null?"":s); }
    String friendly(Exception e){ return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage(); }

    void load(){
        items.clear();
        try{
            JSONArray a=new JSONArray(prefs.getString(KEY_ITEMS,"[]"));
            for(int i=0;i<a.length();i++) items.add(Item.from(a.getJSONObject(i)));
        }catch(Exception ignored){}
    }

    void save(){
        JSONArray a=new JSONArray();
        for(Item it:items) a.put(it.json());
        prefs.edit().putString(KEY_ITEMS,a.toString()).apply();
    }

    void updateStatus(){
        status.setText(items.size()+" отслеживается · API key: "+(prefs.getString(KEY_API,"").trim().isEmpty()?"не задан":"✓"));
    }

    void toast(String s){ Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    int dp(int x){ return Math.round(x*getResources().getDisplayMetrics().density); }

    static class Reply{
        String id="",author="",text="",published="";
        JSONObject json(){
            JSONObject o=new JSONObject();
            try{o.put("id",id);o.put("author",author);o.put("text",text);o.put("published",published);}catch(Exception ignored){}
            return o;
        }
        static Reply from(JSONObject o){
            Reply r=new Reply();
            r.id=o.optString("id","");r.author=o.optString("author","");r.text=o.optString("text","");r.published=o.optString("published","");
            return r;
        }
    }

    static class Item{
        String id="",url="",videoId="",videoTitle="",author="",text="",error="";
        int likes=0,seenLikes=0;
        final ArrayList<Reply> replies=new ArrayList<>();
        final HashSet<String> seenReplyIds=new HashSet<>();

        JSONObject json(){
            JSONObject o=new JSONObject();
            try{
                o.put("id",id);o.put("url",url);o.put("videoId",videoId);o.put("videoTitle",videoTitle);
                o.put("author",author);o.put("text",text);o.put("error",error);o.put("likes",likes);o.put("seenLikes",seenLikes);
                JSONArray r=new JSONArray();for(Reply x:replies)r.put(x.json());o.put("replies",r);
                JSONArray s=new JSONArray();for(String x:seenReplyIds)s.put(x);o.put("seen",s);
            }catch(Exception ignored){}
            return o;
        }

        static Item from(JSONObject o){
            Item it=new Item();
            it.id=o.optString("id","");it.url=o.optString("url","");it.videoId=o.optString("videoId","");
            it.videoTitle=o.optString("videoTitle","");it.author=o.optString("author","");it.text=o.optString("text","");
            it.error=o.optString("error","");it.likes=o.optInt("likes",0);it.seenLikes=o.optInt("seenLikes",0);
            JSONArray r=o.optJSONArray("replies");
            if(r!=null) for(int i=0;i<r.length();i++){ JSONObject x=r.optJSONObject(i); if(x!=null) it.replies.add(Reply.from(x)); }
            JSONArray s=o.optJSONArray("seen");
            if(s!=null) for(int i=0;i<s.length();i++) it.seenReplyIds.add(s.optString(i));
            return it;
        }
    }
}
