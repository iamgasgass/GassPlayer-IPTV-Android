package com.gassplayer.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Category key used by the library filters for "no category". */
const val CATEGORY_NONE = "__none__"

/**
 * Disk-backed catalog. Channels/movies/series/episodes live in SQLite instead of in the Java heap, so a
 * 400k-entry playlist costs a few cache pages of RAM instead of hundreds of MB, and the app starts
 * instantly from the cache (nothing is decoded until a screen scrolls to it).
 *
 * Plain android.database.sqlite: no new dependency, no annotation processor.
 */
class CatalogDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "catalog_v1.db", null, 1) {
    /** Bumped on every write; lists capture it so Compose `remember` keys change when data does. */
    @Volatile var generation: Long = 0L
        private set

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE items(pk INTEGER PRIMARY KEY AUTOINCREMENT, src TEXT NOT NULL, kind INTEGER NOT NULL, cat TEXT, id TEXT NOT NULL, sid TEXT, title TEXT NOT NULL, json TEXT NOT NULL)")
        db.execSQL("CREATE INDEX i_kind ON items(kind, pk)")
        db.execSQL("CREATE INDEX i_cat ON items(kind, cat, pk)")
        db.execSQL("CREATE INDEX i_id ON items(id)")
        db.execSQL("CREATE INDEX i_src ON items(src, kind, sid)")
        db.execSQL("CREATE TABLE meta(src TEXT PRIMARY KEY, updatedAt INTEGER NOT NULL, categories TEXT NOT NULL, cnt INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS items"); db.execSQL("DROP TABLE IF EXISTS meta"); onCreate(db)
    }

    // ---- writes ----------------------------------------------------------------------------------

    @kotlinx.serialization.Serializable
    private data class Cats(val live: List<Category> = emptyList(), val vod: List<Category> = emptyList(), val series: List<Category> = emptyList())

    /** Replaces everything stored for [src] in one transaction (old data stays visible until commit). */
    @Synchronized
    fun replaceSource(src: String, snapshot: SourceSnapshot) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("items", "src=?", arrayOf(src))
            val stmt = db.compileStatement("INSERT INTO items(src,kind,cat,id,sid,title,json) VALUES(?,?,?,?,?,?,?)")
            var count = 0
            for (list in listOf(snapshot.live, snapshot.movies, snapshot.series, snapshot.episodes)) {
                for (item in list) { insert(stmt, src, item); count++ }
            }
            stmt.close()
            val cats = JsonStore.json.encodeToString(Cats.serializer(), Cats(snapshot.liveCategories, snapshot.vodCategories, snapshot.seriesCategories))
            val cv = android.content.ContentValues().apply {
                put("src", src); put("updatedAt", snapshot.updatedAt); put("categories", cats); put("cnt", count)
            }
            db.insertWithOnConflict("meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        generation++
    }

    private val writeLock = java.util.concurrent.locks.ReentrantLock()

    /**
     * Streaming writer: rows go to disk while the playlist is still being parsed, so the heap never
     * holds the whole catalog. Nothing is visible until [commit]; closing without commit rolls back and
     * the previous data of the source stays intact. Use on a single thread (SQLite transaction).
     */
    inner class SourceWriter internal constructor(private val src: String) : AutoCloseable {
        private val db = writableDatabase
        private var closed = false
        private var committed = false
        var count = 0
            private set
        private val stmt: android.database.sqlite.SQLiteStatement = run {
            writeLock.lock()
            try {
                db.beginTransaction()
                try {
                    db.delete("items", "src=?", arrayOf(src))
                    db.compileStatement("INSERT INTO items(src,kind,cat,id,sid,title,json) VALUES(?,?,?,?,?,?,?)")
                } catch (t: Throwable) { runCatching { db.endTransaction() }; throw t }
            } catch (t: Throwable) { writeLock.unlock(); throw t }
        }

        fun add(item: MediaItem) { insert(stmt, src, item); count++ }

        fun commit(updatedAt: Long, live: List<Category>, vod: List<Category>, series: List<Category>) {
            val cats = JsonStore.json.encodeToString(Cats.serializer(), Cats(live, vod, series))
            val cv = android.content.ContentValues().apply {
                put("src", src); put("updatedAt", updatedAt); put("categories", cats); put("cnt", count)
            }
            db.insertWithOnConflict("meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
            committed = true
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { stmt.close() }
            try { db.endTransaction() } finally { writeLock.unlock() }
            if (committed) generation++
        }
    }

    fun <T> writeSource(src: String, block: (SourceWriter) -> T): T = SourceWriter(src).use(block)

    /** Replaces the episodes of one series (lazy per-series fetch from the panel). */
    @Synchronized
    fun replaceEpisodes(src: String, seriesId: String, episodes: List<MediaItem>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("items", "src=? AND kind=? AND sid=?", arrayOf(src, MediaKind.EPISODE.ordinal.toString(), seriesId))
            val stmt = db.compileStatement("INSERT INTO items(src,kind,cat,id,sid,title,json) VALUES(?,?,?,?,?,?,?)")
            for (e in episodes) insert(stmt, src, e)
            stmt.close()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        generation++
    }

    private fun insert(stmt: android.database.sqlite.SQLiteStatement, src: String, item: MediaItem) {
        stmt.clearBindings()
        stmt.bindString(1, src)
        stmt.bindLong(2, item.kind.ordinal.toLong())
        categoryOf(item)?.let { stmt.bindString(3, it) }
        stmt.bindString(4, item.id)
        item.seriesId?.let { stmt.bindString(5, it) }
        stmt.bindString(6, item.title)
        stmt.bindString(7, JsonStore.json.encodeToString(MediaItem.serializer(), item))
        stmt.executeInsert()
    }

    /** Drops data of sources that no longer exist. */
    @Synchronized
    fun pruneExcept(keep: Collection<String>) {
        val db = writableDatabase
        val known = metaSources()
        val stale = known.filter { it !in keep }
        if (stale.isEmpty()) return
        db.beginTransaction()
        try {
            for (s in stale) { db.delete("items", "src=?", arrayOf(s)); db.delete("meta", "src=?", arrayOf(s)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        generation++
    }

    @Synchronized
    fun clearAll() {
        val db = writableDatabase
        db.delete("items", null, null); db.delete("meta", null, null)
        generation++
    }

    // ---- reads -----------------------------------------------------------------------------------

    data class SourceMeta(val updatedAt: Long, val count: Int, val liveCategories: List<Category>, val vodCategories: List<Category>, val seriesCategories: List<Category>)

    fun metaSources(): List<String> = readableDatabase.rawQuery("SELECT src FROM meta", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    fun sourceMeta(src: String): SourceMeta? = readableDatabase.rawQuery("SELECT updatedAt, cnt, categories FROM meta WHERE src=?", arrayOf(src)).use { c ->
        if (!c.moveToFirst()) return null
        val cats = runCatching { JsonStore.json.decodeFromString(Cats.serializer(), c.getString(2)) }.getOrDefault(Cats())
        SourceMeta(c.getLong(0), c.getInt(1), cats.live, cats.vod, cats.series)
    }

    fun cachedSourceCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM meta", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun findById(id: String): MediaItem? = readableDatabase.rawQuery("SELECT json FROM items WHERE id=? LIMIT 1", arrayOf(id)).use { c ->
        if (c.moveToFirst()) decode(c.getString(0)) else null
    }

    fun episodesFor(src: String, seriesId: String): List<MediaItem> =
        query("SELECT json FROM items WHERE src=? AND kind=? AND sid=?", arrayOf(src, MediaKind.EPISODE.ordinal.toString(), seriesId))

    /** Next/previous live channel of the same source in playlist order (what channel-up/down uses). */
    fun liveNeighbor(src: String, id: String, forward: Boolean): MediaItem? {
        val op = if (forward) ">" else "<"
        val order = if (forward) "ASC" else "DESC"
        val sql = "SELECT json FROM items WHERE kind=? AND src=? AND pk $op (SELECT pk FROM items WHERE id=? AND src=? AND kind=? LIMIT 1) ORDER BY pk $order LIMIT 1"
        val live = MediaKind.LIVE.ordinal.toString()
        return query(sql, arrayOf(live, src, id, src, live)).firstOrNull()
    }

    fun byIds(kind: MediaKind, ids: Set<String>, sources: List<String>): List<MediaItem> {
        if (ids.isEmpty() || sources.isEmpty()) return emptyList()
        val out = ArrayList<MediaItem>()
        for (chunk in ids.chunked(400)) {
            val marks = chunk.joinToString(",") { "?" }
            out += query("SELECT json FROM items WHERE kind=? AND id IN ($marks)", arrayOf(kind.ordinal.toString()) + chunk.toTypedArray())
        }
        return out.filter { it.sourceId in sources }
    }

    fun sameTitle(kind: MediaKind, title: String, excludeId: String, sources: List<String>): List<MediaItem> =
        query("SELECT json FROM items WHERE kind=? AND title=? COLLATE NOCASE AND id<>?", arrayOf(kind.ordinal.toString(), title, excludeId))
            .filter { it.sourceId in sources }

    fun search(text: String, limit: Int, sources: List<String>): List<MediaItem> {
        if (text.isBlank() || sources.isEmpty()) return emptyList()
        val escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val marks = sources.joinToString(",") { "?" }
        return query(
            "SELECT json FROM items WHERE title LIKE ? ESCAPE '\\' AND src IN ($marks) LIMIT ?",
            arrayOf("%$escaped%") + sources.toTypedArray() + limit.toString()
        )
    }

    fun categoryCounts(kind: Int, sources: List<String>): Map<String?, Int> {
        if (sources.isEmpty()) return emptyMap()
        val marks = sources.joinToString(",") { "?" }
        return readableDatabase.rawQuery("SELECT cat, COUNT(*) FROM items WHERE kind=? AND src IN ($marks) GROUP BY cat", arrayOf(kind.toString()) + sources.toTypedArray()).use { c ->
            val out = HashMap<String?, Int>()
            while (c.moveToNext()) out[if (c.isNull(0)) null else c.getString(0)] = c.getInt(1)
            out
        }
    }

    internal fun query(sql: String, args: Array<String>): List<MediaItem> =
        readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<MediaItem>(c.count)
            while (c.moveToNext()) out += decode(c.getString(0))
            out
        }

    internal fun scalar(sql: String, args: Array<String>): Int =
        readableDatabase.rawQuery(sql, args).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun decode(json: String): MediaItem = JsonStore.json.decodeFromString(MediaItem.serializer(), json)

    companion object {
        fun categoryOf(item: MediaItem): String? = (item.categoryId ?: item.group)?.takeIf { it.isNotBlank() }
    }
}

/**
 * A read-only List<MediaItem> whose elements are fetched from [CatalogDb] page by page (200 rows,
 * 8 pages cached). It is a normal `List`, so Compose lazy grids and the existing screens work
 * unchanged, but memory stays flat however big the playlist is.
 */
class DbList internal constructor(
    internal val db: CatalogDb,
    internal val kind: MediaKind,
    internal val sources: List<String>,
    internal val category: String? = null,
    private val generation: Long = db.generation,
    internal val excluded: Set<String> = emptySet()
) : java.util.AbstractList<MediaItem>(), RandomAccess {

    private val pages = object : LinkedHashMap<Int, List<MediaItem>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<MediaItem>>?): Boolean = size > MAX_PAGES
    }
    private val cachedSize: Int by lazy { if (sources.isEmpty()) 0 else db.scalar("SELECT COUNT(*) FROM items WHERE ${whereSql()}", whereArgs()) }

    private fun whereSql(): String {
        val sb = StringBuilder("kind=? AND src IN (").append(sources.joinToString(",") { "?" }).append(")")
        when (category) { null -> {}; CATEGORY_NONE -> sb.append(" AND cat IS NULL"); else -> sb.append(" AND cat=?") }
        // Parental-locked ids are excluded in SQL (quoted literals): filtering in Kotlin would pull the whole catalog into RAM.
        if (excluded.isNotEmpty()) sb.append(" AND id NOT IN (").append(excluded.joinToString(",") { "'" + it.replace("'", "''") + "'" }).append(")")
        return sb.toString()
    }

    private fun whereArgs(): Array<String> {
        val args = ArrayList<String>()
        args += kind.ordinal.toString(); args += sources
        if (category != null && category != CATEGORY_NONE) args += category
        return args.toTypedArray()
    }

    /** Same list restricted to one category ([CATEGORY_NONE] = uncategorised, null = all). */
    fun withCategory(cat: String?): DbList = DbList(db, kind, sources, cat, generation, excluded)

    /** Same list without the given ids (e.g. parental locks). */
    fun excluding(ids: Set<String>): DbList = if (ids.isEmpty()) this else DbList(db, kind, sources, category, generation, excluded + ids)

    override val size: Int get() = cachedSize

    override fun get(index: Int): MediaItem {
        if (index < 0 || index >= cachedSize) throw IndexOutOfBoundsException("$index/$cachedSize")
        val p = index / PAGE
        val page = synchronized(pages) { pages[p] } ?: loadPage(p).also { synchronized(pages) { pages[p] = it } }
        // The table may have been rewritten under a stale list: never crash the UI, show a blank cell.
        return page.getOrNull(index - p * PAGE) ?: PLACEHOLDER
    }

    private fun loadPage(p: Int): List<MediaItem> =
        db.query("SELECT json FROM items WHERE ${whereSql()} ORDER BY pk LIMIT $PAGE OFFSET ${p * PAGE}", whereArgs())

    // Cheap identity: without this, Compose `remember(items)` would compare two lists element by
    // element (= load the entire catalog) every time a new CatalogState is published.
    override fun equals(other: Any?): Boolean =
        other is DbList && other.db === db && other.kind == kind && other.category == category && other.sources == sources && other.generation == generation && other.excluded == excluded
    override fun hashCode(): Int = ((kind.ordinal * 31 + (category?.hashCode() ?: 0)) * 31 + sources.hashCode()) * 31 + generation.hashCode() + excluded.hashCode()

    private companion object {
        const val PAGE = 200
        const val MAX_PAGES = 8
        val PLACEHOLDER = MediaItem(id = "", sourceId = "", kind = MediaKind.LIVE, title = "", streamUrl = "")
    }
}

/** Concatenated read-only view (live + movies + series + episodes) with no copying. */
class ConcatList(private val parts: List<List<MediaItem>>) : java.util.AbstractList<MediaItem>(), RandomAccess {
    override val size: Int get() = parts.sumOf { it.size }
    override fun get(index: Int): MediaItem {
        var i = index
        for (p in parts) { if (i < p.size) return p[i]; i -= p.size }
        throw IndexOutOfBoundsException(index.toString())
    }
}

// ---- helpers that use SQL when the list is disk-backed and fall back to plain filtering otherwise ----

/** Items of one library group; [filter] is a category id, [CATEGORY_NONE] or null (all). */
fun List<MediaItem>.inCategory(filter: String?): List<MediaItem> {
    if (filter == null) return this
    if (this is DbList) return withCategory(filter)
    return filter { item ->
        val key = CatalogDb.categoryOf(item)
        if (filter == CATEGORY_NONE) key == null else key == filter
    }
}

fun List<MediaItem>.categoryCounts(): Map<String?, Int> =
    if (this is DbList) db.categoryCounts(kind.ordinal, sources)
    else groupingBy { CatalogDb.categoryOf(it) }.eachCount()

/** Removes [ids] (parental locks) — in SQL for disk-backed lists, so nothing is materialised. */
fun List<MediaItem>.withoutIds(ids: Set<String>): List<MediaItem> =
    if (ids.isEmpty()) this else if (this is DbList) excluding(ids) else filterNot { it.id in ids }

fun List<MediaItem>.onlyIds(ids: Set<String>): List<MediaItem> =
    if (this is DbList) db.byIds(kind, ids, sources) else filter { it.id in ids }

fun List<MediaItem>.sourceIds(): Set<String> =
    if (this is DbList) sources.toSet() else mapTo(HashSet()) { it.sourceId }

fun List<MediaItem>.sameTitleAs(item: MediaItem): List<MediaItem> =
    if (this is DbList) db.sameTitle(item.kind, item.title, item.id, sources)
    else filter { it.kind == item.kind && it.id != item.id && it.title.equals(item.title, true) }
