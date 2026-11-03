package uk.co.nofrills.oldcrowndisplay;

import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Small LAN-only HTTP control server. Do not forward its port to the Internet. */
final class RemoteServer {
    interface Controller {
        JSONObject state() throws Exception;
        JSONObject command(Map<String,String> fields) throws Exception;
        JSONObject upload(String folder,String filename,byte[] content) throws Exception;
        JSONObject delete(String folder,String filename) throws Exception;
    }
    private final int port;
    private final String password;
    private final Controller controller;
    private final byte[] page;
    private volatile boolean alive;
    private ServerSocket server;
    private Thread acceptThread;
    private ExecutorService workers;
    private static final int MAX_UPLOAD=80*1024*1024;
    RemoteServer(int port,String password,Controller controller,byte[] page) {
        this.port=port;this.password=password;this.controller=controller;this.page=page;
    }
    synchronized void start() throws IOException {
        if(alive)return;
        server=new ServerSocket();server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port),12);
        workers=Executors.newFixedThreadPool(4);alive=true;
        acceptThread=new Thread(()->{
            while(alive)try {Socket sock=server.accept();sock.setSoTimeout(20000);workers.execute(()->handle(sock));}
            catch(Exception ignored){if(!alive)break;}
        },"Crown Remote HTTP");acceptThread.setDaemon(true);acceptThread.start();
    }
    synchronized void stop() {
        alive=false;
        try{if(server!=null)server.close();}catch(Exception ignored){}
        if(workers!=null)workers.shutdownNow();
    }
    private void handle(Socket socket) {
        try(Socket s=socket) {
            InputStream in=new BufferedInputStream(s.getInputStream());OutputStream out=s.getOutputStream();
            String start=readLine(in);if(start==null)return;
            String[] req=start.split(" ");if(req.length<2){send(out,400,"text/plain","Bad request".getBytes(StandardCharsets.UTF_8));return;}
            String method=req[0],target=req[1];
            Map<String,String> headers=new HashMap<>();String line;
            while((line=readLine(in))!=null&&!line.isEmpty()){
                int colon=line.indexOf(':');if(colon>0)headers.put(line.substring(0,colon).toLowerCase(Locale.US).trim(),line.substring(colon+1).trim());
            }
            if(!authorized(headers.get("authorization"))){
                byte[] msg="Authentication required".getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"Old Crown Display\"\r\nCache-Control: no-store\r\nContent-Length: "+msg.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(msg);out.flush();return;
            }
            String origin=headers.get("origin");String host=headers.get("host");
            if(!"GET".equals(method)&&origin!=null&&host!=null) {
                if(!origin.equals("http://"+host)&&!origin.equals("https://"+host)){
                    send(out,403,"text/plain","Origin rejected".getBytes(StandardCharsets.UTF_8));return;
                }
            }
            String path=target.split("\\?",2)[0];
            if("GET".equals(method)&&"/".equals(path)){send(out,200,"text/html; charset=utf-8",page);return;}
            if("GET".equals(method)&&"/api/state".equals(path)){json(out,200,controller.state());return;}
            if(!"POST".equals(method)){send(out,404,"text/plain","Not found".getBytes(StandardCharsets.UTF_8));return;}
            int length;try {length=Integer.parseInt((headers.containsKey("content-length")?headers.get("content-length"):"-1"));}catch(Exception e){length=-1;}
            if(length<0||length>MAX_UPLOAD){send(out,413,"text/plain","Invalid body size".getBytes(StandardCharsets.UTF_8));return;}
            byte[] body=new byte[length];int pos=0;while(pos<length){int n=in.read(body,pos,length-pos);if(n<0)throw new EOFException();pos+=n;}
            Map<String,String> params=parse(target.contains("?")?target.substring(target.indexOf('?')+1):"");
            if("/api/command".equals(path))json(out,200,controller.command(parse(new String(body,StandardCharsets.UTF_8))));
            else if("/api/upload".equals(path))json(out,200,controller.upload(params.get("folder"),params.get("name"),body));
            else if("/api/delete".equals(path))json(out,200,controller.delete(params.get("folder"),params.get("name")));
            else send(out,404,"text/plain","Not found".getBytes(StandardCharsets.UTF_8));
        }catch(Exception ignored){}
    }
    private boolean authorized(String header) {
        if(header==null||!header.startsWith("Basic "))return false;
        try {
            String given=new String(Base64.decode(header.substring(6),Base64.DEFAULT),StandardCharsets.UTF_8);
            String expected="admin:"+password;
            byte[] a=given.getBytes(StandardCharsets.UTF_8),b=expected.getBytes(StandardCharsets.UTF_8);
            return java.security.MessageDigest.isEqual(a,b);
        }catch(Exception e){return false;}
    }
    private static String readLine(InputStream in)throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();int ch;
        while((ch=in.read())!=-1){if(ch=='\n')break;if(ch!='\r')b.write(ch);if(b.size()>8192)throw new IOException("Header too long");}
        if(ch==-1&&b.size()==0)return null;
        return b.toString("UTF-8");
    }
    private static Map<String,String> parse(String input)throws UnsupportedEncodingException {
        Map<String,String> m=new HashMap<>();if(input.isEmpty())return m;
        for(String pair:input.split("&")){String[] kv=pair.split("=",2);m.put(URLDecoder.decode(kv[0],"UTF-8"),kv.length>1?URLDecoder.decode(kv[1],"UTF-8"):"");}
        return m;
    }
    private static void json(OutputStream out,int status,JSONObject data)throws IOException {send(out,status,"application/json; charset=utf-8",data.toString().getBytes(StandardCharsets.UTF_8));}
    private static void send(OutputStream out,int code,String type,byte[] bytes)throws IOException {
        out.write(("HTTP/1.1 "+code+" "+(code==200?"OK":"Error")+"\r\nContent-Type: "+type+"\r\nX-Content-Type-Options: nosniff\r\nCache-Control: no-store\r\nContent-Security-Policy: default-src 'self' 'unsafe-inline'\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);out.flush();
    }
}
