import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Le robot Ourlet. Il envoie de vrais clients à ta boutique, mesure ce qu'ils vivent,
 * raconte tout au centre et te montre ta page « Ma boutique » sur le port 8090.
 * Le contrat de ta boutique :
 *   GET  /produits   -> 200 et une liste JSON de variantes : [{"id":1,"produit":"Hoodie oversize","taille":"M","prix":59}, ...]
 *   POST /commandes  -> corps JSON {"variante":1,"client":"Léa"} ; 201 = commande confirmée, 409 = plus en stock
 * Lancement : java Robot.java --nom "Pave Club" [--centre https://...] [--boutique http://localhost:8080] [--port 8090]
 */
public class Robot {
    static String nom = "", id = "", centre = "", boutique = "http://localhost:8080", github = System.getenv().getOrDefault("GITHUB_USER", "");
    static int port = 8090;
    static final String[] CLIENTS = {"Léa", "Manon", "Yassine", "Jules", "Aya", "Louis", "Inaya", "Mathis", "Lou", "Rayan", "Alice", "Kenza", "Hugo", "Nora", "Sami"};

    // réglages donnés par le centre (valeurs par défaut si le centre ne répond pas)
    static volatile double debit = 1;
    static volatile int patience = 30;
    static volatile String centreJson = "null";
    static volatile boolean centreOk = false;
    static volatile long remise = -1;

    // mesures
    record Produit(long id, String nom, String taille, int prix) {}
    record Cmd(String client, String produit, int prix, double debut) {}
    static final long T0 = System.nanoTime();
    static double t() { return (System.nanoTime() - T0) / 1e9; }
    static final Map<Long, Cmd> enCours = new ConcurrentHashMap<>();
    static final Map<Integer, Double> visites = new ConcurrentHashMap<>();
    static final AtomicInteger servies = new AtomicInteger(), parties = new AtomicInteger(), erreurs = new AtomicInteger(), ruptures = new AtomicInteger(), sansAchat = new AtomicInteger();
    static final DoubleAdder ca = new DoubleAdder(), perdu = new DoubleAdder();
    static volatile double derniereVisite = 0;
    static volatile boolean eteinte = true;
    static volatile int statutProduits = 0;
    static volatile List<Produit> catalogue = List.of();

    static HttpClient http;

    public static void main(String[] a) throws Exception {
        for (int i = 0; i + 1 < a.length; i += 2) switch (a[i]) {
            case "--nom" -> nom = a[i + 1].trim();
            case "--centre" -> centre = a[i + 1].replaceAll("/+$", "");
            case "--boutique" -> boutique = a[i + 1].replaceAll("/+$", "");
            case "--port" -> port = Integer.parseInt(a[i + 1]);
            case "--id" -> id = a[i + 1];
        }
        if (nom.isBlank()) { System.err.println("Il manque le nom : --nom \"Ma boutique\""); System.exit(2); }
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).executor(Executors.newVirtualThreadPerTaskExecutor()).connectTimeout(Duration.ofSeconds(5)).build();
        inscrire();

        HttpServer srv = HttpServer.create(new InetSocketAddress(port), 0);
        srv.setExecutor(Executors.newFixedThreadPool(4));
        srv.createContext("/", x -> {
            String p = x.getRequestURI().getPath();
            if (p.equals("/etat")) envoyer(x, 200, "application/json; charset=utf-8", etat().getBytes(StandardCharsets.UTF_8));
            else if (p.equals("/") || p.equals("/ma-boutique")) envoyer(x, 200, "text/html; charset=utf-8", Files.readAllBytes(dossier().resolve("ma-boutique.html")));
            else envoyer(x, 404, "text/plain", "introuvable".getBytes());
        });
        srv.start();

        ScheduledExecutorService h = Executors.newScheduledThreadPool(3);
        h.scheduleAtFixedRate(Robot::sonder, 0, 500, TimeUnit.MILLISECONDS);
        if (!centre.isBlank()) {
            h.scheduleAtFixedRate(Robot::lireConfig, 0, 2, TimeUnit.SECONDS);
            h.scheduleAtFixedRate(Robot::raconter, 1, 1, TimeUnit.SECONDS);
        }
        System.out.println("Robot prêt pour « " + nom + " » : ta page Ma boutique est sur le port " + port + ".");

        // la boucle des clients : toutes les boutiques reçoivent les mêmes clients, dans le même ordre, aux mêmes moments.
        // Le client numéro k est le même partout : seule la boutique fait la différence.
        long dernier = -1;
        while (true) {
            double d = debit;
            if (d <= 0) { dernier = -1; Thread.sleep(200); continue; }
            long k = (long) Math.floor(System.currentTimeMillis() / 1000.0 * d);
            if (dernier < 0 || k - dernier > 5) dernier = k - 1;
            while (dernier < k) client(++dernier);
            Thread.sleep(5);
        }
    }

    static Path dossier() { return Path.of(System.getProperty("robot.dir", Path.of("robot").toFile().exists() ? "robot" : ".")); }

    /** Un client : s'il y a un catalogue, il commande ; sinon il regarde les rayons et repart. */
    static void client(long i) {
        List<Produit> cat = catalogue;
        Random rnd = new Random(i * 2654435761L + 97);
        String qui = CLIENTS[rnd.nextInt(CLIENTS.length)];
        if (cat.isEmpty()) {
            http.sendAsync(HttpRequest.newBuilder(URI.create(boutique + "/produits")).timeout(Duration.ofSeconds(patience)).build(), HttpResponse.BodyHandlers.discarding())
                .whenComplete((r, e) -> { if (e == null) sansAchat.incrementAndGet(); });
            return;
        }
        Produit p = cat.get(rnd.nextInt(cat.size()));
        Cmd c = new Cmd(qui, p.nom() + (p.taille().isBlank() ? "" : ", taille " + p.taille()), p.prix(), t());
        enCours.put(i, c);
        String corps = "{\"variante\":" + p.id() + ",\"client\":\"" + qui + "\"}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(boutique + "/commandes")).timeout(Duration.ofSeconds(patience))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(corps)).build();
        http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).whenComplete((r, e) -> {
            enCours.remove(i);
            if (e == null && r.statusCode() >= 200 && r.statusCode() < 300) { servies.incrementAndGet(); ca.add(p.prix()); }
            else if (e == null && r.statusCode() == 409) ruptures.incrementAndGet();
            else { if (e != null && (e instanceof HttpTimeoutException || e.getCause() instanceof HttpTimeoutException)) parties.incrementAndGet(); else erreurs.incrementAndGet(); perdu.add(p.prix()); }
        });
    }

    /** Toutes les 500 ms, un visiteur ouvre le catalogue : on mesure le temps d'affichage et on garde le catalogue. */
    static final AtomicInteger nv = new AtomicInteger();
    static void sonder() {
        int i = nv.incrementAndGet(); double d = t(); visites.put(i, d);
        http.sendAsync(HttpRequest.newBuilder(URI.create(boutique + "/produits")).timeout(Duration.ofSeconds(patience)).build(), HttpResponse.BodyHandlers.ofString())
            .whenComplete((r, e) -> {
                visites.remove(i);
                if (e != null) { Throwable c = e.getCause() != null ? e.getCause() : e; if (c instanceof ConnectException) { eteinte = true; catalogue = List.of(); statutProduits = 0; } return; }
                eteinte = false; derniereVisite = t() - d; statutProduits = r.statusCode();
                if (r.statusCode() == 200) catalogue = lireCatalogue(r.body()); else catalogue = List.of();
            });
    }

    static List<Produit> lireCatalogue(String corps) {
        try {
            Object o = Json.lire(corps);
            if (!(o instanceof List<?> l)) return List.of();
            List<Produit> res = new ArrayList<>();
            for (Object e : l) if (e instanceof Map<?, ?> m && m.get("id") instanceof Number n) {
                Object pn = m.containsKey("produit") ? m.get("produit") : m.get("nom");
                Object pr = m.get("prix");
                res.add(new Produit(n.longValue(), pn == null ? "Article " + n : String.valueOf(pn), m.get("taille") == null ? "" : String.valueOf(m.get("taille")), pr instanceof Number x ? x.intValue() : 0));
            }
            res.sort(Comparator.comparingLong(Produit::id)); // même ordre partout, quel que soit l'ordre renvoyé
            return res;
        } catch (Exception e) { return List.of(); }
    }

    // ---------------------------------------------------------------- le centre
    static void inscrire() {
        if (centre.isBlank()) { id = id.isBlank() ? (github.isBlank() ? nom : github) : id; return; }
        try {
            String u = centre + "/api/inscription?nom=" + enc(nom) + "&github=" + enc(github) + (id.isBlank() ? "" : "&id=" + enc(id));
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) { Object o = Json.lire(r.body()); id = String.valueOf(((Map<?, ?>) o).get("id")); centreOk = true; return; }
            System.err.println(r.body()); System.exit(3); // nom refusé ou déjà pris
        } catch (Exception e) {
            System.err.println("Le centre ne répond pas (" + e.getClass().getSimpleName() + ") : ta boutique tourne quand même, hors classement.");
            if (id.isBlank()) id = github.isBlank() ? nom : github;
        }
    }
    static void lireConfig() {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(centre + "/api/config?id=" + enc(id))).timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) { centreOk = false; return; }
            Map<?, ?> m = (Map<?, ?>) Json.lire(r.body());
            debit = ((Number) m.get("debit")).doubleValue(); patience = ((Number) m.get("patience")).intValue();
            long r2 = m.get("remise") instanceof Number x ? x.longValue() : 0;
            if (remise >= 0 && r2 != remise) { servies.set(0); parties.set(0); erreurs.set(0); ruptures.set(0); sansAchat.set(0); ca.reset(); perdu.reset(); } // l'enseignant a remis les compteurs à zéro
            remise = r2;
            centreJson = r.body(); centreOk = true;
        } catch (Exception e) { centreOk = false; }
    }
    static void raconter() {
        try { http.send(HttpRequest.newBuilder(URI.create(centre + "/api/mesures?id=" + enc(id))).timeout(Duration.ofSeconds(3)).POST(HttpRequest.BodyPublishers.ofString(etat())).build(), HttpResponse.BodyHandlers.discarding()); }
        catch (Exception e) { centreOk = false; }
    }

    // ---------------------------------------------------------------- l'état, lu par Ma boutique et envoyé au centre
    static String etat() {
        double now = t();
        List<Cmd> ec = new ArrayList<>(enCours.values()); ec.sort(Comparator.comparingDouble(Cmd::debut));
        double visiteMax = 0; for (double d : visites.values()) visiteMax = Math.max(visiteMax, now - d);
        StringBuilder s = new StringBuilder();
        s.append(String.format(Locale.ROOT, "{\"heure\":%d,\"t\":%.2f,\"nom\":%s,\"id\":%s,\"debit\":%.3f,\"patience\":%d,\"servies\":%d,\"parties\":%d,\"erreurs\":%d,\"ruptures\":%d,\"sansAchat\":%d,\"ca\":%.0f,\"perdu\":%.0f,\"enCours\":%d,\"plusVieille\":%.1f,\"visite\":%.2f,\"visiteAttente\":%.1f,\"eteinte\":%b,\"statutProduits\":%d,\"produits\":%d,\"centreOk\":%b,\"centre\":%s,\"attente\":[",
            System.currentTimeMillis(), now, js(nom), js(id), debit, patience, servies.get(), parties.get(), erreurs.get(), ruptures.get(), sansAchat.get(), ca.sum(), perdu.sum(),
            ec.size(), ec.isEmpty() ? 0 : now - ec.get(0).debut(), derniereVisite, visiteMax, eteinte, statutProduits, catalogue.size(), centreOk, centreOk ? centreJson : "null"));
        for (int i = 0; i < Math.min(6, ec.size()); i++) { Cmd c = ec.get(i); s.append(String.format(Locale.ROOT, "%s[%s,%s,%d,%.0f]", i > 0 ? "," : "", js(c.client()), js(c.produit()), c.prix(), now - c.debut())); }
        return s.append("]}").toString();
    }

    // ---------------------------------------------------------------- outils
    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    static String js(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    static void envoyer(HttpExchange x, int code, String type, byte[] b) throws IOException {
        x.getResponseHeaders().set("Content-Type", type); x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(code, b.length); try (OutputStream o = x.getResponseBody()) { o.write(b); }
    }

    /** Un lecteur JSON minimal (objets, listes, textes, nombres, vrai, faux, null). */
    static final class Json {
        final String s; int i;
        Json(String s) { this.s = s; }
        static Object lire(String s) { Json j = new Json(s); j.blancs(); return j.valeur(); }
        void blancs() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object valeur() {
            blancs(); char c = s.charAt(i);
            if (c == '{') { i++; Map<String, Object> m = new LinkedHashMap<>(); blancs(); if (s.charAt(i) == '}') { i++; return m; }
                while (true) { blancs(); String k = texte(); blancs(); i++; m.put(k, valeur()); blancs(); if (s.charAt(i++) == '}') return m; } }
            if (c == '[') { i++; List<Object> l = new ArrayList<>(); blancs(); if (s.charAt(i) == ']') { i++; return l; }
                while (true) { l.add(valeur()); blancs(); if (s.charAt(i++) == ']') return l; } }
            if (c == '"') return texte();
            if (s.startsWith("true", i)) { i += 4; return true; }
            if (s.startsWith("false", i)) { i += 5; return false; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int d = i; while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.parseDouble(s.substring(d, i));
        }
        String texte() {
            StringBuilder b = new StringBuilder(); i++;
            while (s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c == '\\') { char e = s.charAt(i++); switch (e) { case 'n' -> b.append('\n'); case 't' -> b.append('\t'); case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; } default -> b.append(e); } }
                else b.append(c);
            }
            i++; return b.toString();
        }
    }
}
