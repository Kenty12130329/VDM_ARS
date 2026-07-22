package com.fujitsu.robot;

import com.fujitsu.vdmj.values.Value;
import com.fujitsu.vdmj.values.IntegerValue;
import java.io.*;
import java.util.*;

public class ArsVDMController implements VDMController, AutoCloseable {
    private final String jarPath;
    private final String vdmPath;
    private final String className;
    private final List<String> variableNames;
    
    private Process process;
    private BufferedReader reader;
    private BufferedWriter writer;
    private boolean hasPendingExecution = false;
    
    public ArsVDMController(String jarPath, String vdmPath) {
        this.jarPath = jarPath;
        this.vdmPath = vdmPath;
        
        String detectedClass = extractClassNameFromVdmpp(vdmPath);
        this.className = detectedClass;
        
        List<String> vars = new ArrayList<>();
        if (detectedClass.toLowerCase().contains("convenipayment")) {
            vars.add("pending_invoices");
            vars.add("paid_invoices");
            if (contentHasField(vdmPath, "current_session")) {
                vars.add("current_session");
            }
        } else {
            vars.add("starting");
            vars.add("student_login_status");
            vars.add("borrowable_books");
            vars.add("borrowed_books");
            vars.add("guest_user_login_status");
            vars.add("continued_login_confirmation");
        }
        this.variableNames = vars;
    }

    private String extractClassNameFromVdmpp(String vdmPath) {
        try {
            String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(vdmPath)), "UTF-8");
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("class\\s+([A-Za-z0-9_]+)");
            java.util.regex.Matcher m = p.matcher(content);
            if (m.find()) {
                return m.group(1);
            }
        } catch (Exception e) {
            // ignore
        }
        return "ConveniPayment44";
    }

    private boolean contentHasField(String vdmPath, String fieldName) {
        try {
            String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(vdmPath)), "UTF-8");
            return content.contains(fieldName);
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public void init() throws Exception {
        if (process != null) {
            close();
        }
        
        ProcessBuilder pb = new ProcessBuilder("java", "-jar", jarPath, "-vdmpp", "-i", vdmPath);
        pb.redirectErrorStream(true);
        process = pb.start();
        
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), "UTF-8"));
        
        // Wait until Interpreter started
        readUntilReady();
        
        // Initialize instance
        sendAndReceive("create test := new " + className + "()");
        hasPendingExecution = false;
    }
    
    private void readUntilReady() throws Exception {
        StringBuilder sb = new StringBuilder();
        long limit = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < limit) {
            if (reader.ready()) {
                int c = reader.read();
                if (c == -1) break;
                sb.append((char) c);
                String current = sb.toString();
                if (current.contains("Interpreter started")) {
                    break;
                }
            } else {
                Thread.sleep(50);
            }
        }
        readUntilPrompt();
    }
    
    private void readUntilPrompt() throws Exception {
        StringBuilder sb = new StringBuilder();
        long limit = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < limit) {
            if (reader.ready()) {
                int c = reader.read();
                if (c == -1) break;
                sb.append((char) c);
                if (sb.toString().endsWith("> ") || sb.toString().endsWith(">")) {
                    return;
                }
            } else {
                Thread.sleep(10);
            }
        }
        throw new Exception("Timeout waiting for interpreter prompt. Output so far: " + sb.toString());
    }
    private void clearBuffer() throws Exception {
        long lastCharTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - lastCharTime < 20) {
            if (reader.ready()) {
                reader.read();
                lastCharTime = System.currentTimeMillis();
            } else {
                Thread.sleep(5);
            }
        }
    }
    
    private String sendAndReceive(String cmd) throws Exception {
        // 送信前に念のため残っているものをすべてクリア
        clearBuffer();
        
        writer.write(cmd);
        writer.newLine();
        writer.flush();
        
        StringBuilder sb = new StringBuilder();
        long limit = System.currentTimeMillis() + 5000;
        long lastReadTime = System.currentTimeMillis();
        
        while (System.currentTimeMillis() < limit) {
            if (reader.ready()) {
                int c = reader.read();
                if (c == -1) break;
                sb.append((char) c);
                lastReadTime = System.currentTimeMillis();
            } else {
                // 50ms 文字が届かなければ、出力完了判定を行う
                if (System.currentTimeMillis() - lastReadTime > 50) {
                    String current = sb.toString().trim();
                    if (current.equals(">") || current.endsWith("\n>") || current.endsWith("MainThread>")) {
                        break;
                    }
                }
                Thread.sleep(10);
            }
        }
        
        String output = sb.toString().trim();
        if (output.endsWith(">")) {
            output = output.substring(0, output.length() - 1).trim();
        }
        
        if (output.startsWith("=")) {
            output = output.substring(1).trim();
        }
        
        return output;
    }
    
    @Override
    public void executeCommand(String cmd) throws Exception {
        if (hasPendingExecution) {
            // すでに checkPrecondition 内で実行されているのでスキップ
            hasPendingExecution = false;
        } else {
            sendAndReceive("print test." + cmd);
        }
    }
    
    @Override
    public boolean checkPrecondition(String cmd) throws Exception {
        // 実際に実行を試みる。事前条件を満たさない場合はErrorが発生する。
        String res = sendAndReceive("print test." + cmd);
        System.out.println("  [Debug Pre] cmd: " + cmd + ", res: " + res);
        
        String trimmedRes = res.trim();
        if (trimmedRes.startsWith("Runtime: Error") || trimmedRes.startsWith("Error ") || 
            trimmedRes.startsWith("Error:") || trimmedRes.startsWith("FATAL:") || 
            trimmedRes.startsWith("syntax error")) {
            return false;
        }
        
        // 成功した場合、実行済みフラグを立てる
        hasPendingExecution = true;
        return true;
    }


    
    @Override
    public Map<String, Value> getVariables() throws Exception {
        Map<String, Value> map = new HashMap<>();
        String res = sendAndReceive("print test");
        
        // オブジェクトダンプ行を探す (例: test = alla{#1, ...} または = ConveniPayment44{#1, ...})
        String dump = null;
        for (String line : res.split("\n")) {
            line = line.trim();
            if (line.contains("{#")) {
                dump = line;
                break;
            }
        }
        
        if (dump == null) {
            throw new Exception("Failed to find object dump in VDMJ output: " + res);
        }
        
        int braceStart = dump.indexOf('{');
        if (braceStart == -1) {
            throw new Exception("Invalid object dump format: " + dump);
        }
        
        String content = dump.substring(braceStart + 1, dump.length() - 1).trim();
        
        // オブジェクトID（例：#1）を取り除く
        int firstComma = content.indexOf(',');
        if (firstComma != -1) {
            content = content.substring(firstComma + 1).trim();
        }
        
        // カンマ区切りで各フィールドをパースする（括弧のネストを考慮）
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int bracketDepth = 0;
        int parenDepth = 0;
        boolean inQuote = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '"' && (i == 0 || content.charAt(i-1) != '\\')) {
                inQuote = !inQuote;
            }
            if (!inQuote) {
                if (c == '{' || c == '[') bracketDepth++;
                else if (c == '}' || c == ']') bracketDepth--;
                else if (c == '(') parenDepth++;
                else if (c == ')') parenDepth--;
            }
            if (c == ',' && bracketDepth == 0 && parenDepth == 0 && !inQuote) {
                parts.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString().trim());
        }
        
        for (String part : parts) {
            int colonIdx = part.indexOf(':');
            if (colonIdx != -1) {
                String name = part.substring(0, colonIdx).trim();
                String valStr = part.substring(colonIdx + 1).trim();
                if (valStr.startsWith("=")) {
                    valStr = valStr.substring(1).trim();
                }
                
                if (variableNames.contains(name)) {
                    try {
                        long val = Long.parseLong(valStr);
                        map.put(name, new IntegerValue(val));
                    } catch (NumberFormatException e) {
                        map.put(name, new StringValue(valStr));
                    }
                }
            }
        }
        
        return map;
    }
    
    @Override
    public void restoreState(Map<String, Value> state) throws Exception {
        // ランダム探索ではバックトラックを行わないため、何もしない
    }

    
    @Override
    public void close() {
        if (process != null) {
            try {
                if (process.isAlive()) {
                    writer.write("quit");
                    writer.newLine();
                    writer.flush();
                }
            } catch (Exception e) {
                // Ignore
            }
            process.destroy();
            process = null;
        }
    }
}
