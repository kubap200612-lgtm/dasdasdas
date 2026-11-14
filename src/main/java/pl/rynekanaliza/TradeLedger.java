package pl.rynekanaliza;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Historia zakupów i sprzedaży (zakładka Historia): każdy zakup zrobiony przez BK z ceną, a sprzedaż dopiero wtedy, gdy
 * naprawdę nastąpiła (licznik sprzedaży BK + rozpoznany przedmiot). Bez sprzedaży wpis zostaje "jeszcze się nie sprzedał".
 * Zysk = to, co zostało po podatku ze sprzedaży, minus koszt sprzedanych sztuk (strata jest ujemna).
 * Sprzedaż trafia do najstarszych niesprzedanych zakupów tego przedmiotu (ten sam wpis BK, a bez niego ta sama nazwa).
 *
 * Plik: config/rynek-analiza-historia.json.
 */
public final class TradeLedger {
    /** Jeden zakup BK i jego sprzedaż. */
    public static final class Entry {
        public long t;            // kiedy kupione
        public String key = "";   // klucz przedmiotu (wpis BK); pusty, gdy zakup nie pasował do żadnego wpisu
        public String name = "";  // nazwa przedmiotu
        public int count;         // ile sztuk kupiono
        public double cost;       // ile zapłacono za całość
        public int sold;          // ile sztuk już sprzedano
        public double soldGross;  // za ile sprzedano (cena wystawki, przed podatkiem)
        public double soldNet;    // ile dostałeś po podatku
        public long soldAt;       // kiedy ostatnia sprzedaż

        public boolean fullySold() { return sold >= count; }

        public double costPP() { return count > 0 ? cost / count : cost; }

        /** Zysk (+) albo strata (-) ze sprzedanych sztuk (po podatku); 0, gdy nic się nie sprzedało. */
        public double profit() { return sold <= 0 ? 0 : soldNet - costPP() * sold; }
    }

    public static final class Data {
        public List<Entry> entries = new ArrayList<>();
    }

    /** Podsumowanie całej historii. */
    public static final class Totals {
        public double spent;       // ile mod wydał na wszystkie zakupy
        public double soldGross;   // za ile sprzedano (przed podatkiem)
        public double soldNet;     // ile wróciło po podatku
        public double profit;      // łączny zysk ze sprzedanych (po podatku; straty odjęte)
        public double unsoldCost;  // ile kosztowały jeszcze niesprzedane sztuki
        public int bought, soldPieces, unsoldPieces, purchases;
    }

    static int MAX_ENTRIES = 2000; // nie final - testy zmniejszają
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    Data data = new Data();

    public TradeLedger(Path file) {
        this.file = file;
    }

    public static TradeLedger load() {
        TradeLedger l = new TradeLedger(FabricLoader.getInstance().getConfigDir().resolve("rynek-analiza-historia.json"));
        l.read();
        return l;
    }

    void read() {
        try {
            if (file != null && Files.exists(file)) {
                Data d = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
                if (d != null && d.entries != null) {
                    d.entries.removeIf(e -> e == null);
                    for (Entry e : d.entries) {
                        if (e.key == null) e.key = "";
                        if (e.name == null) e.name = "";
                        e.count = Math.max(1, e.count);
                        e.sold = Math.max(0, Math.min(e.count, e.sold));
                    }
                    data = d;
                }
            }
        } catch (Exception e) {
            System.err.println("[rynek-analiza] nie udało się wczytać historii zakupów: " + e);
        }
    }

    public void save() {
        if (file == null) return;
        try {
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[rynek-analiza] nie udało się zapisać historii zakupów: " + e);
        }
    }

    // ---------- zdarzenia ----------
    /** Zakup zrobiony przez BK: count sztuk za total (cena całej oferty). */
    public void onPurchase(String key, String name, int count, double total, long now) {
        if (!(total > 0) || Double.isInfinite(total)) return;
        Entry e = new Entry();
        e.t = now;
        e.key = key == null ? "" : key;
        e.name = clean(name);
        e.count = Math.max(1, count);
        e.cost = total;
        data.entries.add(e);
        trim();
        save();
    }

    /**
     * Sprzedaż pieces sztuk za gross (przed podatkiem; net = po podatku): trafia do najstarszych niesprzedanych zakupów
     * tego przedmiotu - najpierw ten sam wpis BK, potem ta sama nazwa. Sztuki bez zakupu w historii (np. kupione przed
     * włączeniem historii albo nie przez BK) są pomijane.
     * @return ile sztuk przypisano do zakupów
     */
    public int onSale(String key, String name, int pieces, double gross, double net, long now) {
        if (pieces <= 0 || !(net > 0)) return 0;
        int left = pieces;
        left = assign(key == null ? "" : key, null, left, pieces, gross, net, now);
        if (left > 0) left = assign(null, norm(name), left, pieces, gross, net, now);
        int covered = pieces - left;
        if (covered > 0) save();
        return covered;
    }

    private int assign(String key, String nameNorm, int left, int pieces, double gross, double net, long now) {
        for (Entry e : data.entries) {
            if (left <= 0) break;
            if (e.fullySold()) continue;
            if (key != null && (key.isEmpty() || !key.equals(e.key))) continue;
            if (nameNorm != null && (nameNorm.isEmpty() || !nameNorm.equals(norm(e.name)))) continue;
            int take = Math.min(left, e.count - e.sold);
            e.sold += take;
            e.soldGross += gross * take / pieces;
            e.soldNet += net * take / pieces;
            e.soldAt = now;
            left -= take;
        }
        return left;
    }

    /** Czas najstarszego niesprzedanego zakupu przedmiotu nie starszego niż maxAge (0 = brak). */
    public long oldestUnsold(String key, long now, long maxAge) {
        if (key == null || key.isEmpty()) return 0;
        long best = 0;
        for (Entry e : data.entries) {
            if (e.fullySold() || !key.equals(e.key) || now - e.t > maxAge) continue;
            if (best == 0 || e.t < best) best = e.t;
        }
        return best;
    }

    // ---------- odczyt ----------
    /** Wpisy od najnowszego. */
    public List<Entry> newestFirst() {
        List<Entry> out = new ArrayList<>(data.entries);
        out.sort((a, b) -> Long.compare(b.t, a.t));
        return out;
    }

    public Totals totals() {
        Totals s = new Totals();
        for (Entry e : data.entries) {
            s.purchases++;
            s.spent += e.cost;
            s.bought += e.count;
            s.soldPieces += e.sold;
            s.soldGross += e.soldGross;
            s.soldNet += e.soldNet;
            s.profit += e.profit();
            s.unsoldPieces += e.count - e.sold;
            s.unsoldCost += e.costPP() * (e.count - e.sold);
        }
        return s;
    }

    public int size() {
        return data.entries.size();
    }

    // ---------- pomocnicze ----------
    /** Najstarsze w pełni sprzedane wpisy wypadają pierwsze, gdy historia przekroczy MAX_ENTRIES. */
    private void trim() {
        while (data.entries.size() > MAX_ENTRIES) {
            int idx = -1;
            for (int i = 0; i < data.entries.size(); i++) {
                if (data.entries.get(i).fullySold()) { idx = i; break; }
            }
            data.entries.remove(idx >= 0 ? idx : 0);
        }
    }

    static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§') { i++; continue; }
            b.append(c);
        }
        return b.toString().trim();
    }

    static String norm(String s) {
        return clean(s).toLowerCase(Locale.ROOT);
    }
}
