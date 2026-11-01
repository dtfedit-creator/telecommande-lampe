package com.frankystudio.lampe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.ConsumerIrManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Télécommande IR pour projecteur YM-600-SS RGB.
 * Onglet 1 : scanner NEC pour retrouver les codes sans la télécommande d'origine.
 * Onglet 2 : télécommande avec les 13 boutons du manuel, assignables depuis le scanner.
 */
public class MainActivity extends Activity {

    // ---- Boutons de la télécommande d'origine (manuel) ----
    static final String[][] BUTTONS = {
            {"on", "ON"}, {"off", "OFF"},
            {"bri_up", "☀ +"}, {"bri_down", "☀ −"},
            {"k_up", "K +"}, {"k_down", "K −"},
            {"night", "☾ Veilleuse"}, {"rgb", "RGB"},
            {"r", "R +"}, {"g", "G +"}, {"b", "B +"}
    };

    // Adresses NEC 8 bits les plus fréquentes sur les lampes / rubans LED chinois, testées en premier
    static final int[] COMMON_ADDR = {0x00, 0x01, 0x80, 0x02, 0x03, 0x04, 0x08, 0x10, 0x20, 0x40, 0xFF, 0x7F};

    static final int C_BG = Color.parseColor("#101114");
    static final int C_CARD = Color.parseColor("#1C1E24");
    static final int C_TEXT = Color.parseColor("#F2F2F2");
    static final int C_MUTED = Color.parseColor("#9AA0AA");
    static final int C_ACCENT = Color.parseColor("#FFB020");
    static final int C_GOOD = Color.parseColor("#2ECC71");
    static final int C_BAD = Color.parseColor("#E5484D");

    ConsumerIrManager ir;
    SharedPreferences prefs;
    final Handler ui = new Handler(Looper.getMainLooper());

    // État du scanner
    int address = 0x00;
    boolean extended = false;
    int command = 0;
    volatile boolean scanning = false;
    volatile int delayMs = 250;
    int commonIndex = 0;
    final ArrayDeque<int[]> history = new ArrayDeque<>(); // {addr, cmd, ext}
    final List<int[]> candidates = new ArrayList<>();

    LinearLayout root, content;
    TextView statusView, codeView;
    EditText addrEdit;
    CheckBox extBox;
    Button autoBtn;
    LinearLayout candidatesBox;
    Button tabScan, tabRemote;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ir = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
        prefs = getSharedPreferences("codes", MODE_PRIVATE);
        loadCandidates();

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(16), dp(28), dp(16), dp(8));

        TextView title = text("Télécommande Lampe", 22, C_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        statusView = text("", 13, C_MUTED);
        root.addView(statusView);
        if (ir == null || !ir.hasIrEmitter()) {
            statusView.setText("⚠ Aucun émetteur infrarouge détecté sur ce téléphone.");
            statusView.setTextColor(C_BAD);
        } else {
            statusView.setText("Émetteur IR OK — vise la bulle noire sur le dessus de la lampe.");
        }

        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(0, dp(12), 0, dp(12));
        tabRemote = button("Télécommande", v -> showRemote());
        tabScan = button("Scanner", v -> showScanner());
        tabs.addView(tabRemote, weight());
        tabs.addView(space(8));
        tabs.addView(tabScan, weight());
        root.addView(tabs);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        if (assignedCount() == 0) showScanner(); else showRemote();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopScan();
    }

    // =====================================================================
    // NEC
    // =====================================================================

    static int[] necPattern(int addr, int cmd, boolean ext) {
        int[] bytes;
        if (ext) bytes = new int[]{addr & 0xFF, (addr >> 8) & 0xFF, cmd & 0xFF, (~cmd) & 0xFF};
        else bytes = new int[]{addr & 0xFF, (~addr) & 0xFF, cmd & 0xFF, (~cmd) & 0xFF};
        int[] p = new int[2 + 64 + 1];
        int i = 0;
        p[i++] = 9000;
        p[i++] = 4500;
        for (int b : bytes) {
            for (int bit = 0; bit < 8; bit++) {
                p[i++] = 562;
                p[i++] = ((b >> bit) & 1) == 1 ? 1687 : 562;
            }
        }
        p[i] = 562;
        return p;
    }

    boolean send(int addr, int cmd, boolean ext) {
        if (ir == null || !ir.hasIrEmitter()) return false;
        try {
            ir.transmit(38000, necPattern(addr, cmd, ext));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static String fmt(int addr, int cmd, boolean ext) {
        String a = ext ? String.format("%04X", addr & 0xFFFF) : String.format("%02X", addr & 0xFF);
        return "adr 0x" + a + " · cmd 0x" + String.format("%02X", cmd & 0xFF);
    }

    // =====================================================================
    // Onglet Télécommande
    // =====================================================================

    void showRemote() {
        stopScan();
        highlightTab(tabRemote, tabScan);
        content.removeAllViews();

        int n = assignedCount();
        TextView info = text(n == 0
                        ? "Aucun bouton n'est encore assigné. Va dans « Scanner » pour retrouver les codes."
                        : n + " / " + BUTTONS.length + " boutons assignés. Appui long sur un bouton = le désassigner.",
                13, C_MUTED);
        info.setPadding(0, 0, 0, dp(12));
        content.addView(info);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        for (String[] b : BUTTONS) {
            final String id = b[0];
            int[] code = getCode(id);
            Button btn = new Button(this);
            btn.setText(b[1]);
            btn.setTextSize(18);
            btn.setAllCaps(false);
            btn.setTextColor(code != null ? C_TEXT : C_MUTED);
            btn.setBackground(rounded(code != null ? colorFor(id) : C_CARD, code == null));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = dp(72);
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            btn.setLayoutParams(lp);

            final boolean repeat = id.startsWith("bri") || id.startsWith("k_") || id.length() == 1;
            btn.setOnTouchListener(new View.OnTouchListener() {
                final Runnable rep = new Runnable() {
                    @Override
                    public void run() {
                        fire(id);
                        ui.postDelayed(this, 220);
                    }
                };
                long downAt;

                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            downAt = System.currentTimeMillis();
                            v.setAlpha(0.6f);
                            if (getCode(id) == null) return true;
                            fire(id);
                            if (repeat) ui.postDelayed(rep, 450);
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            v.setAlpha(1f);
                            ui.removeCallbacks(rep);
                            long held = System.currentTimeMillis() - downAt;
                            if (getCode(id) == null && e.getAction() == MotionEvent.ACTION_UP) {
                                toast("Bouton pas encore assigné — trouve son code dans le Scanner.");
                            } else if (!repeat && held > 900) {
                                confirmUnassign(id);
                            }
                            return true;
                    }
                    return false;
                }
            });
            if (repeat) {
                btn.setOnLongClickListener(v -> false);
            }
            grid.addView(btn);
        }
        content.addView(grid);

        TextView hint = text("Les boutons ☀, K et R/G/B se répètent si tu les maintiens appuyés. "
                + "Pour désassigner l'un d'eux, utilise le bouton ci-dessous.", 12, C_MUTED);
        hint.setPadding(0, dp(12), 0, dp(8));
        content.addView(hint);
        content.addView(button("Gérer les assignations", v -> manageAssignments()));
    }

    void fire(String id) {
        int[] c = getCode(id);
        if (c == null) return;
        new Thread(() -> send(c[0], c[1], c[2] == 1)).start();
    }

    void confirmUnassign(String id) {
        new AlertDialog.Builder(this)
                .setTitle("Désassigner « " + labelFor(id) + " » ?")
                .setPositiveButton("Oui", (d, w) -> {
                    prefs.edit().remove("btn_" + id).apply();
                    showRemote();
                })
                .setNegativeButton("Non", null)
                .show();
    }

    void manageAssignments() {
        List<String> items = new ArrayList<>();
        final List<String> ids = new ArrayList<>();
        for (String[] b : BUTTONS) {
            int[] c = getCode(b[0]);
            if (c != null) {
                items.add(b[1] + "   →   " + fmt(c[0], c[1], c[2] == 1));
                ids.add(b[0]);
            }
        }
        if (ids.isEmpty()) {
            toast("Aucun bouton assigné.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Touche un bouton pour le désassigner")
                .setItems(items.toArray(new String[0]), (d, which) -> confirmUnassign(ids.get(which)))
                .setNegativeButton("Fermer", null)
                .show();
    }

    // =====================================================================
    // Onglet Scanner
    // =====================================================================

    void showScanner() {
        highlightTab(tabScan, tabRemote);
        content.removeAllViews();

        TextView how = text(
                "1. Allume la lampe avec son bouton et mets-la à luminosité moyenne.\n"
                        + "2. Vise la bulle noire du dessus, à 1–2 m.\n"
                        + "3. Lance « Auto ». Dès que la lampe réagit (s'éteint, change de lumière, de couleur…), "
                        + "appuie sur « ÇA A RÉAGI ».\n"
                        + "4. Teste les codes proposés un par un et assigne le bon au bouton correspondant.",
                13, C_MUTED);
        content.addView(how);

        codeView = text("", 26, C_ACCENT);
        codeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeView.setGravity(Gravity.CENTER);
        codeView.setPadding(0, dp(16), 0, dp(8));
        content.addView(codeView);

        // Adresse
        LinearLayout addrRow = new LinearLayout(this);
        addrRow.setGravity(Gravity.CENTER_VERTICAL);
        addrRow.addView(text("Adresse (hex) : ", 14, C_TEXT));
        addrEdit = new EditText(this);
        addrEdit.setText(extended ? String.format("%04X", address) : String.format("%02X", address));
        addrEdit.setTextColor(C_TEXT);
        addrEdit.setSingleLine(true);
        addrEdit.setTypeface(Typeface.MONOSPACE);
        addrRow.addView(addrEdit, new LinearLayout.LayoutParams(dp(90), -2));
        addrRow.addView(button("OK", v -> applyAddress()));
        content.addView(addrRow);

        extBox = new CheckBox(this);
        extBox.setText("Adresse étendue 16 bits");
        extBox.setTextColor(C_MUTED);
        extBox.setChecked(extended);
        extBox.setOnCheckedChangeListener((b, c) -> {
            extended = c;
            applyAddress();
        });
        content.addView(extBox);

        LinearLayout addrNav = new LinearLayout(this);
        addrNav.addView(button("Adresse courante suivante", v -> nextCommonAddress()), weight());
        content.addView(addrNav);

        // Navigation commande
        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(0, dp(8), 0, 0);
        nav.addView(button("◀", v -> step(-1)), weight());
        nav.addView(space(6));
        nav.addView(button("Envoyer", v -> sendCurrent()), weight());
        nav.addView(space(6));
        nav.addView(button("▶", v -> step(1)), weight());
        content.addView(nav);

        autoBtn = button("▶ Auto", v -> toggleScan());
        autoBtn.setBackground(rounded(Color.parseColor("#2A6DF4"), false));
        autoBtn.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, dp(56));
        alp.topMargin = dp(8);
        content.addView(autoBtn, alp);

        // Vitesse
        TextView speedLabel = text("Vitesse : " + delayMs + " ms entre deux codes", 12, C_MUTED);
        speedLabel.setPadding(0, dp(8), 0, 0);
        content.addView(speedLabel);
        SeekBar speed = new SeekBar(this);
        speed.setMax(900);
        speed.setProgress(delayMs - 100);
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                delayMs = p + 100;
                speedLabel.setText("Vitesse : " + delayMs + " ms entre deux codes");
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        content.addView(speed);

        Button reacted = button("ÇA A RÉAGI !", v -> onReacted());
        reacted.setTextSize(20);
        reacted.setTypeface(Typeface.DEFAULT_BOLD);
        reacted.setTextColor(Color.BLACK);
        reacted.setBackground(rounded(C_ACCENT, false));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, dp(84));
        rlp.topMargin = dp(12);
        content.addView(reacted, rlp);

        TextView ct = text("Codes candidats", 16, C_TEXT);
        ct.setTypeface(Typeface.DEFAULT_BOLD);
        ct.setPadding(0, dp(20), 0, dp(4));
        content.addView(ct);
        candidatesBox = new LinearLayout(this);
        candidatesBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(candidatesBox);

        refreshCode();
        refreshCandidates();
    }

    void applyAddress() {
        try {
            int a = Integer.parseInt(addrEdit.getText().toString().trim(), 16);
            address = extended ? (a & 0xFFFF) : (a & 0xFF);
            command = 0;
            refreshCode();
        } catch (NumberFormatException e) {
            toast("Adresse invalide (ex : 00, 80, EF00)");
        }
    }

    void nextCommonAddress() {
        stopScan();
        extended = false;
        if (extBox != null) extBox.setChecked(false);
        commonIndex = (commonIndex + 1) % COMMON_ADDR.length;
        address = COMMON_ADDR[commonIndex];
        command = 0;
        if (addrEdit != null) addrEdit.setText(String.format("%02X", address));
        refreshCode();
    }

    void step(int d) {
        stopScan();
        command = (command + d + 256) % 256;
        sendCurrent();
    }

    void sendCurrent() {
        final int a = address, c = command;
        final boolean x = extended;
        remember(a, c, x);
        refreshCode();
        new Thread(() -> send(a, c, x)).start();
    }

    void remember(int a, int c, boolean x) {
        synchronized (history) {
            history.addLast(new int[]{a, c, x ? 1 : 0});
            while (history.size() > 4) history.removeFirst();
        }
    }

    void toggleScan() {
        if (scanning) stopScan();
        else startScan();
    }

    void startScan() {
        if (ir == null || !ir.hasIrEmitter()) {
            toast("Pas d'émetteur IR.");
            return;
        }
        scanning = true;
        if (autoBtn != null) autoBtn.setText("⏸ Pause");
        new Thread(() -> {
            while (scanning) {
                final int a = address, c = command;
                final boolean x = extended;
                remember(a, c, x);
                send(a, c, x);
                ui.post(this::refreshCode);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ignored) {
                }
                if (!scanning) break;
                command++;
                if (command > 255) {
                    command = 0;
                    if (extended) {
                        address = (address + 1) & 0xFFFF;
                    } else {
                        // Passe à l'adresse courante suivante, puis balaie le reste dans l'ordre
                        commonIndex++;
                        if (commonIndex < COMMON_ADDR.length) address = COMMON_ADDR[commonIndex];
                        else address = nextUntestedAddress(address);
                    }
                    ui.post(() -> {
                        if (addrEdit != null)
                            addrEdit.setText(extended ? String.format("%04X", address) : String.format("%02X", address));
                        toast("Adresse suivante : 0x" + (extended ? String.format("%04X", address) : String.format("%02X", address)));
                    });
                }
            }
        }).start();
    }

    int nextUntestedAddress(int current) {
        int a = current;
        for (int i = 0; i < 256; i++) {
            a = (a + 1) & 0xFF;
            boolean common = false;
            for (int ca : COMMON_ADDR) if (ca == a) common = true;
            if (!common) return a;
        }
        return 0;
    }

    void stopScan() {
        scanning = false;
        if (autoBtn != null) autoBtn.setText("▶ Auto");
    }

    void onReacted() {
        stopScan();
        int added = 0;
        synchronized (history) {
            for (int[] h : history) {
                boolean dup = false;
                for (int[] c : candidates)
                    if (c[0] == h[0] && c[1] == h[1] && c[2] == h[2]) dup = true;
                if (!dup) {
                    candidates.add(0, h.clone());
                    added++;
                }
            }
        }
        saveCandidates();
        refreshCandidates();
        toast(added + " code(s) ajouté(s). Teste-les un par un ci-dessous.");
    }

    void refreshCode() {
        if (codeView != null) codeView.setText(fmt(address, command, extended));
    }

    void refreshCandidates() {
        if (candidatesBox == null) return;
        candidatesBox.removeAllViews();
        if (candidates.isEmpty()) {
            candidatesBox.addView(text("Aucun pour l'instant.", 13, C_MUTED));
            return;
        }
        for (Iterator<int[]> it = candidates.iterator(); it.hasNext(); ) {
            final int[] c = it.next();
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(6), dp(6), dp(6));
            row.setBackground(rounded(C_CARD, false));
            TextView t = text(fmt(c[0], c[1], c[2] == 1), 14, C_TEXT);
            t.setTypeface(Typeface.MONOSPACE);
            row.addView(t, weight());
            row.addView(smallButton("Tester", v -> new Thread(() -> send(c[0], c[1], c[2] == 1)).start()));
            row.addView(smallButton("Assigner", v -> assignDialog(c)));
            row.addView(smallButton("✕", v -> {
                candidates.remove(c);
                saveCandidates();
                refreshCandidates();
            }));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(6);
            candidatesBox.addView(row, lp);
        }
    }

    void assignDialog(int[] c) {
        String[] labels = new String[BUTTONS.length];
        for (int i = 0; i < BUTTONS.length; i++) {
            int[] cur = getCode(BUTTONS[i][0]);
            labels[i] = BUTTONS[i][1] + (cur != null ? "   (déjà : " + fmt(cur[0], cur[1], cur[2] == 1) + ")" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle(fmt(c[0], c[1], c[2] == 1) + " → quel bouton ?")
                .setItems(labels, (d, which) -> {
                    String id = BUTTONS[which][0];
                    prefs.edit().putString("btn_" + id, c[0] + ":" + c[1] + ":" + c[2]).apply();
                    // Astuce : une fois l'adresse trouvée, inutile de balayer ailleurs
                    if (!extended && c[2] == 0) {
                        address = c[0];
                        if (addrEdit != null) addrEdit.setText(String.format("%02X", address));
                    }
                    toast("Assigné à « " + BUTTONS[which][1] + " »");
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // =====================================================================
    // Stockage
    // =====================================================================

    int[] getCode(String id) {
        String s = prefs.getString("btn_" + id, null);
        if (s == null) return null;
        try {
            String[] p = s.split(":");
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
        } catch (Exception e) {
            return null;
        }
    }

    int assignedCount() {
        int n = 0;
        for (String[] b : BUTTONS) if (getCode(b[0]) != null) n++;
        return n;
    }

    void saveCandidates() {
        StringBuilder sb = new StringBuilder();
        for (int[] c : candidates) {
            if (sb.length() > 0) sb.append(";");
            sb.append(c[0]).append(":").append(c[1]).append(":").append(c[2]);
        }
        prefs.edit().putString("candidates", sb.toString()).apply();
    }

    void loadCandidates() {
        candidates.clear();
        String s = prefs.getString("candidates", "");
        if (s.isEmpty()) return;
        for (String part : s.split(";")) {
            try {
                String[] p = part.split(":");
                candidates.add(new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])});
            } catch (Exception ignored) {
            }
        }
    }

    // =====================================================================
    // UI helpers
    // =====================================================================

    String labelFor(String id) {
        for (String[] b : BUTTONS) if (b[0].equals(id)) return b[1];
        return id;
    }

    int colorFor(String id) {
        switch (id) {
            case "on": return Color.parseColor("#1E7F4F");
            case "off": return Color.parseColor("#8E2A2E");
            case "r": return Color.parseColor("#B3262B");
            case "g": return Color.parseColor("#1F8A3A");
            case "b": return Color.parseColor("#2850C8");
            case "rgb": return Color.parseColor("#6B3FA0");
            case "k_up": return Color.parseColor("#B7792A");
            case "k_down": return Color.parseColor("#3D6E9E");
            default: return Color.parseColor("#2C2F38");
        }
    }

    void highlightTab(Button on, Button off) {
        on.setBackground(rounded(C_ACCENT, false));
        on.setTextColor(Color.BLACK);
        off.setBackground(rounded(C_CARD, false));
        off.setTextColor(C_TEXT);
    }

    GradientDrawable rounded(int color, boolean outline) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(color);
        if (outline) g.setStroke(dp(1), Color.parseColor("#3A3D46"));
        return g;
    }

    TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(C_TEXT);
        b.setBackground(rounded(C_CARD, true));
        b.setOnClickListener(l);
        return b;
    }

    Button smallButton(String label, View.OnClickListener l) {
        Button b = button(label, l);
        b.setTextSize(12);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
        lp.leftMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    View space(int w) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(w), 1));
        return v;
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
