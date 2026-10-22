package com.willlauer.summit.data

import java.text.Normalizer

/** Cluster colours, set from the current look (ui/Theme.kt): each sub-cluster its own hue; a family uses its largest's. */
var PALETTE: IntArray = intArrayOf(0xFF7FDBFF.toInt())

/** One embedding space: map positions (flat and 3D), sub-cluster / family per track, and their names and colours. */
class SpaceData(val key: String, val label: String, s: Space, clusters: List<Group>, families: List<Group>) {
    val n = s.x.size
    val x = s.x.toFloatArray()
    val y = s.y.toFloatArray()
    val xyz: Array<FloatArray>? = s.xyz?.map { it.toFloatArray() }?.toTypedArray()
    val cluster = s.c.toIntArray()
    val family = s.f.toIntArray()
    private val leafLabel = clusters.associate { it.id to it.label }
    private val leafFamily = clusters.associate { it.id to (it.family ?: 0) }
    private val famLabel = families.associate { it.id to it.label }
    private val leafSlot = HashMap<Int, Int>()   // palette position of each sub-cluster (colours follow the look)
    private val famSlot = HashMap<Int, Int>()
    private val splitFamilies: Set<Int>

    init {
        clusters.sortedWith(compareBy({ it.family ?: 0 }, { it.id })).forEachIndexed { k, g ->
            leafSlot[g.id] = k
            famSlot.putIfAbsent(g.family ?: 0, k)  // ids are largest-first
        }
        splitFamilies = clusters.groupBy { it.family ?: 0 }.filter { it.value.size > 1 }.keys
    }

    private fun slot(k: Int?) = PALETTE[(k ?: 0) % PALETTE.size]
    fun colorOf(i: Int) = slot(leafSlot[cluster[i]])
    fun leafColor(c: Int) = slot(leafSlot[c])
    fun familyColor(f: Int) = slot(famSlot[f])
    fun familyHue(f: Int) = PALETTE[f % PALETTE.size]
    fun isSplit(f: Int) = f in splitFamilies
    fun familyName(f: Int) = famLabel[f] ?: "Family ${f + 1}"
    fun leafName(c: Int) = leafLabel[c] ?: "Group ${c + 1}"
    fun familyOfLeaf(c: Int) = leafFamily[c] ?: 0
    fun groupName(i: Int): String {
        val f = family[i]
        return if (isSplit(f)) "${familyName(f)} › ${leafName(cluster[i])}" else familyName(f)
    }
}

class Artist(val name: String, val norm: String, val tracks: IntArray) {
    val letter: Char = sortKey(name).firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'
    companion object {
        fun sortKey(name: String) = norm(name).removePrefix("the ").trim()
    }
}

// Compiled once: on Android every Regex() compiles through ICU and holds ~100 KB of native memory until finalized.
private val MARKS = Regex("\\p{Mn}+")
val SPACES = Regex("\\s+")
fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")

// "A ft B", "A feat. B", "A & B", "A, B", "A x B", "A vs B" credit each artist (so the artist list is people, not credits).
private val SPLIT = Regex("\\s+(?:ft\\.?|feat\\.?|featuring|vs\\.?|x|with)\\s+|\\s*[,&]\\s*|\\s+and\\s+(?=[A-Z])", RegexOption.IGNORE_CASE)
fun splitArtists(credit: String): List<String> = credit.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

/** The library as the phone uses it: tracks, spaces, and indexes for search and the artist list. */
class Library(p: LibraryPayload) {
    val tracks = p.tracks
    val n = tracks.size
    val byId: Map<String, Int> = tracks.withIndex().associate { it.value.id to it.index }
    val spaces: Map<String, SpaceData> = p.spaceList.mapNotNull { info ->
        p.spaces[info.key]?.let { info.key to SpaceData(info.key, info.label, it, p.clusters[info.key].orEmpty(), p.families[info.key].orEmpty()) }
    }.toMap()
    val defaultSpace = if (p.defaultSpace in spaces) p.defaultSpace else spaces.keys.firstOrNull() ?: "combined"

    private val titleN = tracks.map { norm(it.name) }
    private val artistN = tracks.map { norm(it.by) }
    private val albumN = tracks.map { norm(it.album ?: "") }
    private val hay = tracks.indices.map { "${titleN[it]} ${artistN[it]} ${albumN[it]}" }

    val artists: List<Artist> = run {
        val m = LinkedHashMap<String, Pair<String, MutableList<Int>>>()
        tracks.forEachIndexed { i, t ->
            for (a in splitArtists(t.by)) m.getOrPut(norm(a)) { a to mutableListOf() }.second.add(i)
        }
        m.map { (k, v) -> Artist(v.first, k, v.second.sortedWith(compareBy({ norm(tracks[it].album ?: "") }, { titleN[it] })).toIntArray()) }
            .sortedWith(compareBy({ if (it.letter == '#') 1 else 0 }, { Artist.sortKey(it.name) }))
    }
    private val artistByNorm = artists.associateBy { it.norm }
    fun artist(name: String) = artistByNorm[norm(name)]

    val byTitle: List<Int> by lazy { tracks.indices.sortedBy { titleN[it].removePrefix("the ") } }
    val byAdded: List<Int> by lazy { tracks.indices.sortedByDescending { tracks[it].added } }
    val byBpm: List<Int> by lazy { tracks.indices.sortedWith(compareBy({ tracks[it].bpm ?: 999 }, { titleN[it] })) }
    fun titleLetter(i: Int): Char = titleN[i].removePrefix("the ").firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'

    /** Every word must appear somewhere in title / artist / album. Title matches rank first, then artist, then album. */
    fun searchSongs(q: String, limit: Int = 200): List<Int> {
        val words = norm(q).split(SPACES).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val whole = words.joinToString(" ")
        return tracks.indices.filter { i -> words.all { hay[i].contains(it) } }
            .sortedBy { i ->
                when {
                    titleN[i].startsWith(whole) -> 0
                    titleN[i].contains(whole) -> 1
                    artistN[i].startsWith(whole) -> 2
                    words.all { titleN[i].contains(it) } -> 3
                    artistN[i].contains(whole) -> 4
                    else -> 5
                }
            }.take(limit)
    }

    fun searchArtists(q: String, limit: Int = 8): List<Artist> {
        val words = norm(q).split(SPACES).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return artists.filter { a -> words.all { a.norm.contains(it) } }
            .sortedWith(compareBy({ if (it.norm.startsWith(words[0])) 0 else 1 }, { -it.tracks.size })).take(limit)
    }
}
