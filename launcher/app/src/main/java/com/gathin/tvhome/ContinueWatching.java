package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Continue watching shelf (config.xml continue_watching), in the Apple TV "Up Next" style: portrait posters, and
 * the picked one opens into a wide card (backdrop with the title's logo); the last one picked stays open when focus
 * leaves the row. Each has its title and "31% · Sci-fi" under it. Read from the TV's Watch Next table (and, with a package filter,
 * the preview channels) when Home comes to the front, off the main thread. Apps that put their artwork in the
 * row's link (Nuvio: poster, backdrop, logo) get the richer cards; others fall back to the row's own image.
 */
final class ContinueWatching {
    private static final int MAX_CARDS = 12;
    /** Card height bounds; the real height fills the room between the clock and the dock (fit). */
    private static final int MIN_CARD_H_DP = 110;
    private static final int MAX_CARD_H_DP = 180;
    private static final float WIDE_RATIO = 16f / 9f;
    private static final float POSTER_RATIO = 0.68f;
    private static final int TEXT_H_DP = 50;
    private static final int PAD_DP = 12;
    private static final int GAP_DP = 22;
    private static final int RADIUS_DP = 14;

    private static final class Item {
        String title, episode, art, poster, posterAlt, backdrop, logo, intentUri, pkg, type, contentId, imdb;
        int season, episodeNo;
        float progress;
    }

    private final Context context;
    private final String packageFilter;
    private final float density;
    private final LinearLayout shelf;
    private final HorizontalScrollView scroller;
    private final LinearLayout row;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, String> genres = new HashMap<>();
    private final Typeface medium, regular;
    private volatile boolean loading;
    private String lastKey = "";
    private List<Item> current = new ArrayList<>();
    private int cardH;
    private int lastFocusedCard;
    /** The card shown open (wide); it stays open when focus leaves the row. */
    private int expanded;
    private final List<Card> cards = new ArrayList<>();
    /** Down from a card: back to the dock tile focus came from. */
    Runnable onDown;

    ContinueWatching(Context context, String packageFilter, float density) {
        this.context = context;
        this.packageFilter = packageFilter == null ? "" : packageFilter.trim().toLowerCase(Locale.ROOT);
        this.density = density;
        cardH = dp(160);
        medium = font(R.string.font_medium);
        regular = font(R.string.font_regular);

        shelf = new LinearLayout(context);
        shelf.setOrientation(LinearLayout.VERTICAL);
        shelf.setClipChildren(false);
        shelf.setClipToPadding(false);
        shelf.setVisibility(View.GONE);
        // The cards take focus only when Up from the dock hands it over (takeFocus), never as the window's first
        // focus on a cold start, and not from the top bar's Down, which belongs to the dock.
        shelf.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        scroller = new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setFocusable(false);
        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(dp(PAD_DP), dp(PAD_DP), dp(PAD_DP), 0);
        scroller.addView(row, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        shelf.addView(scroller, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    View view() {
        return shelf;
    }

    /** Height the shelf takes: the focus room above the cards, the cards and the lines under them. */
    int heightPx() {
        return heightFor(cardH);
    }

    private int heightFor(int h) {
        return dp(PAD_DP + TEXT_H_DP) + h;
    }

    /** Sizes the cards to fill {@code roomPx} (between the clock and the dock); returns the shelf's new height. */
    int fit(int roomPx) {
        int h = Math.max(dp(MIN_CARD_H_DP), Math.min(dp(MAX_CARD_H_DP), roomPx - heightFor(0)));
        if (h != cardH) {
            cardH = h;
            rebuild();
        }
        return heightPx();
    }

    boolean hasCards() {
        return row.getChildCount() > 0;
    }

    /** Focus the card last focused (the first one the first time); false when there is no card to take it. */
    boolean takeFocus() {
        if (shelf.getVisibility() != View.VISIBLE || row.getChildCount() == 0) return false;
        shelf.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        View target = row.getChildAt(Math.max(0, Math.min(lastFocusedCard, row.getChildCount() - 1)));
        return target.requestFocus();
    }

    /** Reads the table again; the cards are rebuilt only when what they show changed. */
    void refresh() {
        if (loading) return;
        loading = true;
        io.execute(() -> {
            List<Item> items;
            try {
                items = query();
            } catch (RuntimeException e) {
                items = new ArrayList<>();   // no READ_TV_LISTINGS, or the provider is gone: nothing to show
            }
            List<Item> result = items;
            main.post(() -> {
                loading = false;
                show(result);
            });
        });
    }

    private List<Item> query() {
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        read(TvContract.WatchNextPrograms.CONTENT_URI, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC", out, seen);
        if (!packageFilter.isEmpty() && out.size() < MAX_CARDS) read(TvContract.PreviewPrograms.CONTENT_URI, null, out, seen);
        return out;
    }

    private void read(Uri uri, String order, List<Item> out, Set<String> seen) {
        String[] cols = {
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
        };
        Cursor c;
        try {
            c = context.getContentResolver().query(uri, cols, null, null, order);
        } catch (RuntimeException e) {
            if (order == null) throw e;
            c = context.getContentResolver().query(uri, cols, null, null, null);   // a provider that rejects the sort
        }
        if (c == null) return;
        try {
            while (c.moveToNext() && out.size() < MAX_CARDS) {
                String pkg = c.getString(7);
                if (!packageFilter.isEmpty() && (pkg == null || !pkg.toLowerCase(Locale.ROOT).contains(packageFilter))) continue;
                Item it = new Item();
                it.pkg = pkg;
                it.title = c.getString(0);
                it.episode = c.getString(1);
                it.art = c.getString(3) != null ? c.getString(3) : c.getString(2);
                it.intentUri = c.getString(4);
                long pos = c.getLong(5);
                long dur = c.getLong(6);
                it.progress = dur > 0 ? Math.max(0f, Math.min(1f, pos / (float) dur)) : 0f;
                if (it.title == null || it.intentUri == null) continue;
                if (!seen.add(it.title + "|" + (it.episode == null ? "" : it.episode))) continue;
                readExtras(it);
                out.add(it);
            }
        } finally {
            c.close();
        }
    }

    /** The artwork and ids an app put in the row's own link (Nuvio does: poster, backdrop, logo, contentId). */
    private static void readExtras(Item it) {
        try {
            Intent i = Intent.parseUri(it.intentUri, Intent.URI_INTENT_SCHEME);
            it.poster = web(i.getStringExtra("poster"));
            it.backdrop = web(i.getStringExtra("backdrop"));
            it.logo = web(i.getStringExtra("logo"));
            it.type = i.getStringExtra("contentType");
            it.contentId = i.getStringExtra("contentId");
            it.season = i.getIntExtra("season", 0);
            it.episodeNo = i.getIntExtra("episode", 0);
            // The IMDb id, from the content id or, for anime listed by MAL/Kitsu id, from an artwork URL.
            String id = it.contentId == null ? "" : it.contentId;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("tt\\d{5,}").matcher(id + " " + it.backdrop + " " + it.poster);
            if (m.find()) it.imdb = m.group();
            // The public portrait poster for that id: used when the link has none, or its "poster" is a wide still.
            if (it.imdb != null) it.posterAlt = "https://images.metahub.space/poster/medium/" + it.imdb + "/img";
            if (it.poster == null) it.poster = it.posterAlt;
        } catch (Exception ignored) {
            // A link that is not an intent: the row's own image and title are enough.
        }
    }

    private static String web(String url) {
        return url != null && (url.startsWith("https://") || url.startsWith("http://")) ? url : null;
    }

    private void show(List<Item> items) {
        StringBuilder key = new StringBuilder();
        for (Item it : items) key.append(it.title).append('|').append(it.episode).append('|').append((int) (it.progress * 100)).append(';');
        if (key.toString().equals(lastKey)) return;
        lastKey = key.toString();
        current = items;
        rebuild();
    }

    private void rebuild() {
        View focused = row.findFocus();
        int focusedIndex = focused == null ? -1 : row.indexOfChild(focused);
        row.removeAllViews();
        cards.clear();
        if (current.isEmpty()) {
            shelf.setVisibility(View.GONE);
            return;
        }
        expanded = Math.max(0, Math.min(expanded, current.size() - 1));
        for (int i = 0; i < current.size(); i++) {
            Card c = card(current.get(i));
            cards.add(c);
            row.addView(c.col);
            c.setOpen(i == expanded, false);
        }
        shelf.setVisibility(View.VISIBLE);
        shelf.setAlpha(1f);
        if (focusedIndex >= 0) row.getChildAt(Math.min(focusedIndex, row.getChildCount() - 1)).requestFocus();
    }

    /** One title: a portrait poster that opens into a wide card (backdrop, logo) while it is the one picked. */
    private final class Card {
        final Item it;
        final LinearLayout col = new LinearLayout(context);
        final FrameLayout box = new FrameLayout(context);
        final ImageView poster = new ImageView(context);
        final FrameLayout wide = new FrameLayout(context);
        final ImageView backdrop = new ImageView(context);
        final ImageView logo = new ImageView(context);
        final View fill = new View(context);
        final View ring = new View(context);
        boolean wideLoaded;
        ValueAnimator anim;

        Card(Item it) {
            this.it = it;
        }

        int posterW() {
            return (int) (cardH * POSTER_RATIO);
        }

        int wideW() {
            return (int) (cardH * WIDE_RATIO);
        }

        /** Opens into the wide card or closes back to the poster, sliding the width (animated when asked). */
        void setOpen(boolean on, boolean animate) {
            if (on && !wideLoaded) {
                wideLoaded = true;
                loadImage(first(it.backdrop, it.art, it.poster), backdrop, wideW(), false, null);
                if (it.logo != null) loadImage(it.logo, logo, (int) (wideW() * 0.5f), true, null);
            }
            int from = col.getLayoutParams().width;
            int to = on ? wideW() : posterW();
            if (anim != null) anim.cancel();
            wide.animate().alpha(on ? 1f : 0f).setDuration(animate ? 260 : 0).start();
            if (!animate || from == to || from <= 0) {
                width(to);
                return;
            }
            anim = ValueAnimator.ofInt(from, to);
            anim.setDuration(260);
            anim.setInterpolator(new DecelerateInterpolator(1.6f));
            anim.addUpdateListener(a -> width((int) a.getAnimatedValue()));
            anim.start();
        }

        void width(int w) {
            col.getLayoutParams().width = w;
            box.getLayoutParams().width = w;
            if (fill.getLayoutParams() != null) fill.getLayoutParams().width = Math.max(dp(4), (int) ((w - 2 * dp(12)) * it.progress));
            col.requestLayout();
        }
    }

    private Card card(Item it) {
        Card k = new Card(it);
        int w = k.posterW();
        LinearLayout col = k.col;
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT);
        colLp.setMarginEnd(dp(GAP_DP));
        col.setLayoutParams(colLp);
        col.setFocusable(true);
        col.setClickable(true);
        col.setClipChildren(false);
        col.setClipToPadding(false);

        FrameLayout box = k.box;
        box.setLayoutParams(new LinearLayout.LayoutParams(w, cardH));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1C1E24);
        bg.setCornerRadius(dp(RADIUS_DP));
        box.setBackground(bg);
        box.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(RADIUS_DP));
            }
        });
        box.setClipToOutline(true);
        box.setElevation(dp(4));

        k.poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(k.poster, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // The wide face: backdrop, a soft floor of shade and the title's logo bottom left, as tvOS draws Up Next.
        k.wide.setAlpha(0f);
        k.backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        k.wide.addView(k.backdrop, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        if (it.logo != null) {
            View scrim = new View(context);
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0x99000000, 0x00000000}));
            k.wide.addView(scrim, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, cardH / 2, Gravity.BOTTOM));
            k.logo.setScaleType(ImageView.ScaleType.FIT_START);
            FrameLayout.LayoutParams logoLp = new FrameLayout.LayoutParams((int) (k.wideW() * 0.5f), (int) (cardH * 0.3f), Gravity.BOTTOM | Gravity.START);
            logoLp.leftMargin = dp(14);
            logoLp.bottomMargin = dp(it.progress > 0f ? 24 : 14);
            k.wide.addView(k.logo, logoLp);
        }
        box.addView(k.wide, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        if (it.progress > 0f) {
            FrameLayout track = new FrameLayout(context);
            GradientDrawable trackBg = new GradientDrawable();
            trackBg.setColor(0x59FFFFFF);
            trackBg.setCornerRadius(dp(2));
            track.setBackground(trackBg);
            GradientDrawable fillBg = new GradientDrawable();
            fillBg.setColor(Color.WHITE);
            fillBg.setCornerRadius(dp(2));
            k.fill.setBackground(fillBg);
            track.addView(k.fill, new FrameLayout.LayoutParams(Math.max(dp(4), (int) ((w - 2 * dp(12)) * it.progress)), FrameLayout.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams trackLp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(4), Gravity.BOTTOM);
            trackLp.leftMargin = dp(12);
            trackLp.rightMargin = dp(12);
            trackLp.bottomMargin = dp(10);
            box.addView(track, trackLp);
        }
        // Edge light that only the focused card shows, the way tvOS lifts a card.
        GradientDrawable ringBg = new GradientDrawable();
        ringBg.setCornerRadius(dp(RADIUS_DP));
        ringBg.setStroke(dp(2), 0xE6FFFFFF);
        k.ring.setBackground(ringBg);
        k.ring.setAlpha(0f);
        box.addView(k.ring, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        col.addView(box);

        TextView title = text(15, 0xF2FFFFFF, medium);
        title.setText(it.title);
        title.setPadding(dp(2), dp(10), dp(2), 0);
        col.addView(title);
        TextView meta = text(12.5f, 0xA6FFFFFF, regular);
        meta.setPadding(dp(2), dp(2), dp(2), 0);
        meta.setText(meta(it, null));
        col.addView(meta);
        if (it.imdb != null) loadGenre(it, meta);

        col.setOnFocusChangeListener((v, focused) -> {
            float s = focused ? 1.04f : 1f;
            box.animate().scaleX(s).scaleY(s).translationZ(focused ? dp(14) : 0).setDuration(160).start();
            k.ring.animate().alpha(focused ? 1f : 0f).setDuration(160).start();
            title.setTextColor(focused ? Color.WHITE : 0xF2FFFFFF);
            if (!focused) {
                // Focus left the row (to the dock or the top bar): lock the cards until Up hands it back.
                col.post(() -> {
                    if (row.findFocus() == null) shelf.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                });
                return;
            }
            int index = cards.indexOf(k);
            lastFocusedCard = index;
            // The picked card opens and the one open before closes; leaving the row keeps the last one open.
            if (index != expanded) {
                if (expanded >= 0 && expanded < cards.size()) cards.get(expanded).setOpen(false, true);
                expanded = index;
                k.setOpen(true, true);
            }
            // Keep the opened card inside the visible part of the shelf, once the widths have settled.
            col.postDelayed(() -> {
                int left = col.getLeft() - dp(PAD_DP);
                int right = col.getRight() + dp(PAD_DP + 40);
                if (left < scroller.getScrollX()) scroller.smoothScrollTo(Math.max(0, left), 0);
                else if (right > scroller.getScrollX() + scroller.getWidth()) scroller.smoothScrollTo(right - scroller.getWidth(), 0);
            }, 280);
        });
        col.setOnClickListener(v -> open(it.pkg, it.intentUri));
        col.setOnKeyListener((v, key, ev) -> {
            if (key != android.view.KeyEvent.KEYCODE_DPAD_DOWN || onDown == null) return false;
            if (ev.getAction() == android.view.KeyEvent.ACTION_DOWN) onDown.run();
            return true;
        });

        loadImage(first(it.poster, it.art, it.backdrop), k.poster, w, false, it.posterAlt);
        return k;
    }

    private TextView text(float sp, int color, Typeface tf) {
        TextView t = new TextView(context);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setShadowLayer(dp(4), 0, dp(1), 0x99000000);
        return t;
    }

    /** "31% · Sci-fi", or for an episode "S1 E2 · 31%". */
    private static String meta(Item it, String genre) {
        boolean spanish = "es".equals(Locale.getDefault().getLanguage());
        List<String> parts = new ArrayList<>();
        if (it.season > 0 && it.episodeNo > 0) parts.add((spanish ? "T" : "S") + it.season + " E" + it.episodeNo);
        else if (it.episode != null && !it.episode.isEmpty()) parts.add(it.episode);
        if (it.progress > 0f) parts.add(Math.max(1, Math.round(it.progress * 100)) + "%");
        if (genre != null && !genre.isEmpty()) parts.add(genre);
        return TextUtils.join(" · ", parts);
    }

    /** The first genre from Cinemeta for an IMDb id, translated where we know the word; cached for the session. */
    private void loadGenre(Item it, TextView into) {
        String type = "movie".equals(it.type) ? "movie" : "series";
        String cacheKey = type + "/" + it.imdb;
        String cached = genres.get(cacheKey);
        if (cached != null) {
            into.setText(meta(it, cached));
            return;
        }
        io.execute(() -> {
            String g = "";
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL("https://v3-cinemeta.strem.io/meta/" + cacheKey + ".json").openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(8000);
                try (InputStream in = conn.getInputStream()) {
                    JSONObject meta = new JSONObject(new String(readAll(in), "UTF-8")).optJSONObject("meta");
                    JSONArray list = meta == null ? null : meta.optJSONArray("genres");
                    if (list == null && meta != null) list = meta.optJSONArray("genre");
                    if (list != null && list.length() > 0) g = translate(list.optString(0));
                }
            } catch (Exception ignored) {
                // No genre: the line keeps the progress alone.
            } finally {
                if (conn != null) conn.disconnect();
            }
            String genre = g;
            main.post(() -> {
                genres.put(cacheKey, genre);
                into.setText(meta(it, genre));
            });
        });
    }

    private static String translate(String g) {
        if (!"es".equals(Locale.getDefault().getLanguage())) return g;
        switch (g.toLowerCase(Locale.ROOT)) {
            case "action": return "Acción";
            case "adventure": return "Aventura";
            case "animation": return "Animación";
            case "biography": return "Biografía";
            case "comedy": return "Comedia";
            case "crime": return "Crimen";
            case "documentary": return "Documental";
            case "family": return "Familia";
            case "fantasy": return "Fantasía";
            case "history": return "Historia";
            case "horror": return "Terror";
            case "music": return "Música";
            case "mystery": return "Misterio";
            case "sci-fi":
            case "science fiction": return "Ciencia ficción";
            case "sport": return "Deporte";
            case "thriller": return "Suspenso";
            case "war": return "Bélica";
            case "news": return "Noticias";
            case "reality-tv": return "Reality";
            default: return g;
        }
    }

    private static String first(String... urls) {
        for (String u : urls) if (u != null && (u.startsWith("https://") || u.startsWith("http://"))) return u;
        return null;
    }

    private void open(String pkg, String intentUri) {
        try {
            if (pkg == null || pkg.isEmpty()) return;
            Intent i = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME);
            // An app's own link, never a component or selector the launcher did not expect to launch.
            i.setSelector(null);
            // Drop any flags the link carried (URI permission grants and the like): only a new task is wanted.
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // The link may only open the app that published the row, not another app's screens.
            if (i.getComponent() != null && !pkg.equals(i.getComponent().getPackageName())) return;
            if (i.getPackage() != null && !pkg.equals(i.getPackage())) return;
            if (i.getComponent() == null) i.setPackage(pkg);
            context.startActivity(i);
        } catch (Exception ignored) {
            // The app was removed or the link is stale: the card does nothing.
        }
    }

    /** Fetches url into the view; a wide picture where a poster belongs is swapped for portraitAlt when there is one. */
    private void loadImage(String url, ImageView into, int targetW, boolean alpha, String portraitAlt) {
        if (url == null) return;
        io.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(8000);
                try (InputStream in = conn.getInputStream()) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = alpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
                    byte[] bytes = readAll(in);
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                    int sample = 1;
                    while (o.outWidth / (sample * 2) >= targetW * 1.1f) sample *= 2;
                    o.inJustDecodeBounds = false;
                    o.inSampleSize = sample;
                    if (portraitAlt != null && !portraitAlt.equals(url) && o.outWidth > o.outHeight) {
                        loadImage(portraitAlt, into, targetW, alpha, null);
                        return;
                    }
                    Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                    if (b != null) main.post(() -> {
                        into.setImageBitmap(b);
                        into.setAlpha(0f);
                        into.animate().alpha(1f).setDuration(220).start();
                    });
                }
            } catch (Exception ignored) {
                // No image: the card keeps its plain background and the title under it.
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0 && out.size() < 4 * 1024 * 1024) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private Typeface font(int res) {
        try {
            return UiFont.get(context, res);
        } catch (RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    void destroy() {
        io.shutdownNow();
    }
}
