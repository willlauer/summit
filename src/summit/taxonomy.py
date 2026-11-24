"""The tag taxonomy and how known data maps onto it. Axes, and the rekordbox My Tag category each goes to (rbsync.CATS):
  Genre                                → Genre
  Beat (pattern), Feel (modifiers)     → Beat
  Mood, Texture, Era, Edit (type)      → Sound
  Vocals, Lead (sound)                 → Components
Era comes from release years (file tags, Discogs) and Edit from the title (known.era_of / known.edits_of); neither is
asked about. Edit is never predicted from audio (RULE_ONLY); Era is, for tracks with no year, once it has examples.

Each tag has a CLAP prompt for its zero-shot score until confirmations train it. `AXES` says how many tags of an axis
a track may carry. The maps translate outside vocabularies (file genre strings, Discogs styles, Last.fm tags) into
tags; a value is a tag name, a family ("@House": somewhere in that family), or {tag: weight} when it's ambiguous.
"""

# Genre: family → {genre: prompt}. A track has one main genre and optionally a second.
GENRES = {
    "House": {
        "House": "classic house music with a four-on-the-floor kick and soulful chords",
        "Deep House": "deep house with warm chords, soft kicks and a mellow groove",
        "Tech House": "tech house with a rolling bassline, crisp percussion and a minimal groove",
        "Progressive House": "progressive house with long builds, melodic synths and big breakdowns",
        "Melodic House & Techno": "melodic house and techno with emotive arpeggios and dark atmospheric pads",
        "Afro House": "afro house with tribal percussion, organic drums and chanted vocals",
        "Bass House": "bass house with a heavy wobbling bassline and a four-on-the-floor kick",
        "Future House": "future house with a metallic plucked bassline and pop vocals",
        "Electro House": "electro house with loud distorted synth leads and big drops",
        "Tropical House": "tropical house with marimba, steel drums and a relaxed summery groove",
        "Nu Disco / Disco": "disco and nu disco with funky basslines, strings and a disco groove",
    },
    "Techno": {
        "Techno": "techno with a pounding kick, hypnotic loops and industrial textures",
        "Peak-time / Hard Techno": "hard peak-time techno, fast, relentless and distorted",
        "Minimal / Deep Techno": "minimal deep techno, sparse, dubby and hypnotic",
    },
    "Trance": {
        "Trance": "trance music with soaring supersaw leads, euphoric breakdowns and arpeggios",
        "Tech Trance": "tech trance, driving and percussive with acid synth lines",
    },
    "UK / Bass": {
        "UK Garage": "uk garage with shuffled skippy two-step drums and chopped vocals",
        "Speed Garage": "speed garage with a shuffled four-on-the-floor beat and a booming off-beat bass",
        "Bassline": "uk bassline house with a wobbling organ bass and four-on-the-floor",
        "Grime": "grime, dark 140 bpm beats with aggressive rap",
        "Dubstep": "dubstep with half-time drums and heavy wobble bass drops",
        "Drum & Bass": "drum and bass, fast 170 bpm breakbeats with a deep rolling bassline",
        "Halftime": "halftime bass music, slow heavy drums at 170 bpm",
        "Breaks": "breakbeat music with chopped breaks and a funky broken rhythm",
    },
    "Festival": {
        "Big Room / Mainstage": "big room festival edm with a huge kick drum and simple synth drop",
        "Future Bass": "future bass with lush detuned supersaw chords and vocal chops",
        "EDM Trap": "edm trap with booming 808s, snare rolls and brass stabs",
        "Hardstyle": "hardstyle with a distorted reverse bass kick at 150 bpm",
    },
    "Pop": {
        "Pop": "mainstream pop song with catchy vocals",
        "Dance Pop": "dance pop song with a club beat and catchy chorus",
        "K-pop": "k-pop song, polished korean pop with group vocals",
        "Synth-pop": "synth-pop with retro synthesizers and new wave vocals",
        "Indie Pop": "indie pop with jangly guitars and dreamy vocals",
    },
    "Hip Hop & R&B": {
        "Hip Hop": "hip hop with rap vocals over a sampled beat",
        "Trap": "trap rap with 808 bass, rolling hi-hats and rap vocals",
        "Cloud Rap": "cloud rap, hazy, atmospheric beats with melodic auto-tuned rap",
        "R&B / Soul": "r&b and soul with smooth sung vocals",
    },
    "Rock": {
        "Alt / Indie Rock": "alternative indie rock band with electric guitars",
        "Pop Rock": "pop rock song with guitars and a big chorus",
        "Punk / Hard Rock": "loud fast punk and hard rock with distorted guitars",
    },
    "Other": {
        "Downtempo / Ambient": "downtempo ambient electronic music, slow and atmospheric",
        "Folk / Country": "acoustic folk and country music",
        "Latin / Reggaeton": "latin reggaeton with a dembow rhythm and spanish vocals",
        "Funk": "funk music with slap bass, horns and a tight groove",
    },
}

BEAT = {  # main pattern: one per track
    "four-on-the-floor": "steady four-on-the-floor kick drum on every beat",
    "2-step": "skippy two-step garage drums where the kick skips beats",
    "breakbeat": "broken breakbeat drums with syncopated kicks and snares",
    "dnb breaks": "fast drum and bass breakbeats at 170 bpm",
    "half-time": "half-time drums with the snare on the third beat, slow heavy groove",
    "trap beat": "trap beat with 808s and rapid rolling hi-hats",
    "dembow": "dembow reggaeton rhythm",
    "live backbeat": "live drum kit playing a rock backbeat",
    "no beat": "ambient music with no drums or beat",
}

FEEL = {  # modifiers: any number
    "swung / shuffled": "swung shuffled groove with a triplet swing feel",
    "off-beat bass": "off-beat bassline hitting between the kicks",
    "off-beat hats": "open hi-hats on the off-beat",
    "syncopated / percussive": "syncopated percussive groove with lots of congas and bongos",
    "polyrhythmic": "polyrhythmic afro percussion with triplet patterns",
    "driving / straight": "straight driving relentless rhythm",
}

MOOD = {  # up to two per track
    "dark": "dark, ominous, brooding music",
    "euphoric": "euphoric, uplifting, joyful music",
    "melancholic": "melancholic, sad, wistful music",
    "warm / soulful": "warm, soulful, comforting music",
    "dreamy / chill": "dreamy, chilled, relaxed music",
    "hypnotic": "hypnotic, repetitive, trance-inducing groove",
    "aggressive": "aggressive, angry, intense music",
    "playful": "playful, bouncy, fun music",
    "sexy / groovy": "sexy, sensual, groovy music",
}

TEXTURE = {  # how it's produced: up to two
    "polished": "clean, polished, glossy modern production",
    "lo-fi / dusty": "lo-fi dusty production with vinyl crackle and muffled sound",
    "gritty / distorted": "gritty, distorted, saturated, rough sound",
    "minimal / sparse": "minimal, sparse arrangement with few elements",
    "lush / layered": "lush, dense, layered arrangement with many sounds",
    "raw / live": "raw live recording of musicians playing together",
}

ERA = {  # one per track, from the release year (known.era_of); the prompts only matter for tracks with no year
    "pre-80s": "music from the 1960s or 1970s, vintage recording",
    "80s": "music from the 1980s with 80s synths and drum machines",
    "90s": "music from the 1990s with 90s production",
    "2000s": "music from the 2000s with 2000s production",
    "2010s": "music from the 2010s with 2010s production",
    "2020s": "recent music from the 2020s with modern production",
}

EDIT = {  # which version this file is, from its title (known.edits_of): up to two, e.g. remix + extended
    "original": "", "extended": "", "remix": "", "edit / bootleg": "", "mashup": "", "live": "",
}

VOCALS = {  # up to two
    "instrumental": "instrumental music with no vocals",
    "vocal chops": "chopped, pitched vocal samples used as a hook",
    "male vocal": "a male singer singing the lead vocal",
    "female vocal": "a female singer singing the lead vocal",
    "rap": "rap vocals, a rapper rapping",
    "spoken word": "spoken word, a talking voice over music",
    "choir / group": "a choir or group of voices singing together",
}

LEAD = {  # the sound carrying the melody or hook: up to three
    "piano": "piano playing chords or the melody",
    "strings": "string section, violins and cellos",
    "guitar": "guitar playing the riff or melody",
    "brass / sax": "brass section or saxophone",
    "synth lead": "a bright synthesizer lead melody",
    "acid (303)": "squelchy acid 303 bassline",
    "organ": "organ chords, house organ stabs",
    "plucks": "short plucked synth notes",
    "pads": "long sustained synth pads and atmospheres",
}

# category → (tags, max per track). Genre's max counts main + second.
AXES = {
    "Genre": ({g: p for fam in GENRES.values() for g, p in fam.items()}, 2),
    "Beat": (BEAT, 1),
    "Feel": (FEEL, None),
    "Mood": (MOOD, 2),
    "Texture": (TEXTURE, 2),
    "Era": (ERA, 1),
    "Edit": (EDIT, 2),
    "Vocals": (VOCALS, 2),
    "Lead": (LEAD, 3),
}
NOT_ASKED = {"Era", "Edit"}  # facts from metadata and titles: shown and correctable in Review, never asked in Tag
RULE_ONLY = {"Edit"}  # never predicted from the audio
FAMILY = {g: fam for fam, gs in GENRES.items() for g in gs}
SEEDED = set(AXES)  # categories the taxonomy owns; user-made Vibe tags are left alone


def rows() -> list[tuple[str, str, str, str | None]]:
    """(category, name, prompt, parent) for every taxonomy tag."""
    return [(cat, name, prompt, FAMILY.get(name)) for cat, (tags, _) in AXES.items() for name, prompt in tags.items()]


# Discogs style → genre. Keyed by "Parent---Style" where the short name is ambiguous, else by the short name.
# Serves both the Discogs API's styles and the audio model's style predictions (same vocabulary).
STYLE_MAP = {
    # House
    "House": "House", "Garage House": "House", "Ghetto House": "House", "Hip-House": "House", "Italo House": "House",
    "Euro House": "House", "Acid House": "House", "Tribal House": "House", "Hard House": "House", "Jazzdance": "House",
    "Deep House": "Deep House", "Tech House": "Tech House", "Progressive House": "Progressive House",
    "Electro House": "Electro House", "Tropical House": "Tropical House",
    "Nu-Disco": "Nu Disco / Disco", "Disco": "Nu Disco / Disco", "Euro-Disco": "Nu Disco / Disco",
    "Italo-Disco": "Nu Disco / Disco", "Boogie": "Nu Disco / Disco", "Hi NRG": "Nu Disco / Disco",
    "Tribal": {"Afro House": 0.5, "Tech House": 0.5},
    # Techno
    "Techno": "Techno", "Acid": "Techno", "Electronic---Industrial": "Peak-time / Hard Techno", "EBM": "Techno",
    "Hard Techno": "Peak-time / Hard Techno", "Schranz": "Peak-time / Hard Techno",
    "Minimal": "Minimal / Deep Techno", "Minimal Techno": "Minimal / Deep Techno", "Deep Techno": "Minimal / Deep Techno",
    "Dub Techno": "Minimal / Deep Techno",
    # Trance
    "Trance": "Trance", "Progressive Trance": "Trance", "Psy-Trance": "Trance", "Goa Trance": "Trance",
    "Hard Trance": "Trance", "Tech Trance": "Tech Trance",
    # UK / Bass. The audio model's "Bassline" fires on most bass house, so it's split.
    "UK Garage": "UK Garage", "Speed Garage": "Speed Garage", "Bassline": {"Bassline": 0.5, "Bass House": 0.5},
    "Grime": "Grime", "Dubstep": "Dubstep", "Drum n Bass": "Drum & Bass", "Jungle": "Drum & Bass", "Halftime": "Halftime",
    "Breaks": "Breaks", "Breakbeat": "Breaks", "Progressive Breaks": "Breaks", "Big Beat": "Breaks", "Broken Beat": "Breaks",
    # Festival
    "Hardstyle": "Hardstyle", "Jumpstyle": "Hardstyle", "Gabber": "Hardstyle", "Electronic---Hardcore": "Hardstyle",
    "Happy Hardcore": "Hardstyle", "Hands Up": {"Big Room / Mainstage": 0.5, "Dance Pop": 0.5},
    # Pop
    "Dance-pop": "Dance Pop", "Europop": "Dance Pop", "Eurodance": "Dance Pop", "Italodance": "Dance Pop",
    "K-pop": "K-pop", "Synth-pop": "Synth-pop", "Synthwave": "Synth-pop", "Electronic---New Wave": "Synth-pop",
    "Electroclash": "Synth-pop", "Indie Pop": "Indie Pop",
    "Ballad": "Pop", "Vocal": "Pop", "Bubblegum": "Pop", "J-pop": "Pop", "City Pop": "Pop", "Schlager": "Pop",
    # Hip Hop & R&B
    "Electronic---Hip Hop": "Hip Hop", "Boom Bap": "Hip Hop", "Conscious": "Hip Hop", "Gangsta": "Hip Hop",
    "Hardcore Hip-Hop": "Hip Hop", "Pop Rap": "Hip Hop", "Jazzy Hip-Hop": "Hip Hop", "G-Funk": "Hip Hop",
    "Thug Rap": "Hip Hop", "Crunk": "Hip Hop", "Bounce": "Hip Hop", "Miami Bass": "Hip Hop", "Horrorcore": "Hip Hop",
    "Hip Hop---Instrumental": "Hip Hop", "Trap": "Trap", "Cloud Rap": "Cloud Rap",
    "RnB/Swing": "R&B / Soul", "Contemporary R&B": "R&B / Soul", "Neo Soul": "R&B / Soul", "Soul": "R&B / Soul",
    "New Jack Swing": "R&B / Soul", "Swingbeat": "R&B / Soul", "Rhythm & Blues": "R&B / Soul",
    "UK Street Soul": "R&B / Soul", "Funk / Soul---Gospel": "R&B / Soul",
    # Rock
    "Alternative Rock": "Alt / Indie Rock", "Indie Rock": "Alt / Indie Rock", "Grunge": "Alt / Indie Rock",
    "Shoegaze": "Alt / Indie Rock", "Post-Punk": "Alt / Indie Rock", "Emo": "Alt / Indie Rock",
    "Garage Rock": "Alt / Indie Rock", "Lo-Fi": "Alt / Indie Rock", "Post Rock": "Alt / Indie Rock",
    "Brit Pop": "Alt / Indie Rock", "Dream Pop": "Alt / Indie Rock",
    "Pop Rock": "Pop Rock", "Soft Rock": "Pop Rock", "AOR": "Pop Rock", "Arena Rock": "Pop Rock", "Power Pop": "Pop Rock",
    "Classic Rock": "Pop Rock", "Folk Rock": "Pop Rock", "Country Rock": "Pop Rock", "Southern Rock": "Pop Rock",
    "Blues Rock": "Pop Rock",
    "Punk": "Punk / Hard Rock", "Pop Punk": "Punk / Hard Rock", "Hard Rock": "Punk / Hard Rock",
    "Heavy Metal": "Punk / Hard Rock", "Nu Metal": "Punk / Hard Rock", "Metalcore": "Punk / Hard Rock",
    "Post-Hardcore": "Punk / Hard Rock", "Rock---Hardcore": "Punk / Hard Rock",
    # Other
    "Downtempo": "Downtempo / Ambient", "Ambient": "Downtempo / Ambient", "Chillwave": "Downtempo / Ambient",
    "Electronic---Trip Hop": "Downtempo / Ambient", "Hip Hop---Trip Hop": "Downtempo / Ambient",
    "IDM": "Downtempo / Ambient", "Leftfield": "Downtempo / Ambient", "New Age": "Downtempo / Ambient",
    "Dark Ambient": "Downtempo / Ambient", "Drone": "Downtempo / Ambient", "Modern Classical": "Downtempo / Ambient",
    "Future Jazz": "Downtempo / Ambient", "Acid Jazz": "Downtempo / Ambient", "Lounge": "Downtempo / Ambient",
    "Folk": "Folk / Country", "Country": "Folk / Country", "Bluegrass": "Folk / Country", "Honky Tonk": "Folk / Country",
    "Acoustic": "Folk / Country",
    "Reggaeton": "Latin / Reggaeton", "Electronic---Latin": "Latin / Reggaeton", "Cumbia": "Latin / Reggaeton",
    "Dancehall": "Latin / Reggaeton", "Salsa": "Latin / Reggaeton", "Merengue": "Latin / Reggaeton",
    "Funk": "Funk", "P.Funk": "Funk", "Free Funk": "Funk", "Jazz-Funk": "Funk", "Funk / Soul---Afrobeat": "Funk",
}

# The library's file genre strings (lowercased). None = says nothing useful.
FILE_GENRE_MAP = {
    "pop": "Pop", "dance": None, "rock": "@Rock", "alternative": "Alt / Indie Rock", "house": "House",
    "tech house": "Tech House", "progressive house": "Progressive House", "rap": "@Hip Hop & R&B",
    "dance pop": "Dance Pop", "dance / pop": "Dance Pop", "dance / electro pop": "Dance Pop", "top 40": "Pop",
    "top 40 / dance": "Dance Pop", "electronica": None, "electronic": None, "edm": None, "bass": None, "other": None,
    "mainstream": None, "classics": None, "urban": "@Hip Hop & R&B", "dance & house": "@House",
    "future house": "Future House", "electro house": "Electro House", "bass house": "Bass House",
    "indie rock": "Alt / Indie Rock", "deep house": "Deep House", "melodic house & techno": "Melodic House & Techno",
    "hip hop": "@Hip Hop & R&B", "hip-hop": "@Hip Hop & R&B", "hip-hop/rap": "@Hip Hop & R&B",
    "hip hop/rap": "@Hip Hop & R&B", "hip-hop / r&b": "@Hip Hop & R&B",
    "future bass": "Future Bass", "r&b": "R&B / Soul", "r&b/soul": "R&B / Soul", "nu r&b": "R&B / Soul",
    "dubstep": "Dubstep", "140 / deep dubstep / grime": {"Dubstep": 0.5, "Grime": 0.5}, "drum & bass": "Drum & Bass",
    "trap": {"EDM Trap": 0.5, "Trap": 0.5}, "trap / future bass": {"EDM Trap": 0.5, "Future Bass": 0.5},
    "techno": "Techno", "techno (peak time / driving)": "Peak-time / Hard Techno", "techno (peak time)": "Peak-time / Hard Techno",
    "country & folk": "Folk / Country", "country": "Folk / Country", "singer/songwriter": "Folk / Country",
    "disco": "Nu Disco / Disco", "nu disco": "Nu Disco / Disco", "indie dance / nu disco": "Nu Disco / Disco",
    "indie dance": None, "mainstage": "Big Room / Mainstage", "big room": "Big Room / Mainstage",
    "big room/edm": "Big Room / Mainstage", "speed house": {"Bass House": 0.6, "Electro House": 0.4},
    "afro house": "Afro House", "punk": "Punk / Hard Rock", "hard rock": "Punk / Hard Rock",
    "trance": "Trance", "trance (main floor)": "Trance", "trance (raw / deep / hypnotic)": "Trance", "hard trance": "Trance",
    "uk garage": "UK Garage", "uk garage / bassline": {"UK Garage": 0.5, "Bassline": 0.5},
    "garage / bassline / grime": {"UK Garage": 0.4, "Bassline": 0.4, "Grime": 0.2},
    "rock/alternative": "Alt / Indie Rock", "alternative rock": "Alt / Indie Rock",
    "latin": "Latin / Reggaeton", "latin pop": "Latin / Reggaeton", "latin / reggaeton": "Latin / Reggaeton",
    "moombahton": "Latin / Reggaeton", "breaks / breakbeat / uk bass": "Breaks", "jersey club": None,
    "tropical house": "Tropical House", "minimal": "Minimal / Deep Techno", "minimal / deep tech": "Minimal / Deep Techno",
    "electro/progressive": {"Electro House": 0.5, "Progressive House": 0.5},
    "dance commercial/mainstream club": None, "dance/mainstream club": None, "dance/vocal/club": None,
    "grime": "Grime", "funky / groove / jackin' house": "House", "jackin house": "House", "house/vocal house": "House",
    "dance/ house": "House", "acid house": "House", "indie pop": "Indie Pop",
    "hard dance / hardcore / neo rave": "Hardstyle", "soundtrack": None, "accapellas/dj tools": None, "twerk": None,
    "bass / club": None,
}

# Last.fm tag (lowercased) → mood. Genre tags go through the genre maps (by lowercased name).
LASTFM_MOOD = {
    "dark": "dark", "ominous": "dark", "sinister": "dark", "brooding": "dark", "gloomy": "dark",
    "euphoric": "euphoric", "uplifting": "euphoric", "happy": "euphoric", "feel good": "euphoric", "anthem": "euphoric",
    "melancholic": "melancholic", "melancholy": "melancholic", "sad": "melancholic", "bittersweet": "melancholic",
    "heartbreak": "melancholic", "depressing": "melancholic",
    "soulful": "warm / soulful", "warm": "warm / soulful", "smooth": "warm / soulful",
    "chill": "dreamy / chill", "chillout": "dreamy / chill", "dreamy": "dreamy / chill", "relaxing": "dreamy / chill",
    "mellow": "dreamy / chill", "calm": "dreamy / chill", "ethereal": "dreamy / chill", "atmospheric": "dreamy / chill",
    "hypnotic": "hypnotic", "trippy": "hypnotic", "repetitive": "hypnotic", "mesmerizing": "hypnotic",
    "aggressive": "aggressive", "angry": "aggressive", "intense": "aggressive", "brutal": "aggressive",
    "fun": "playful", "playful": "playful", "quirky": "playful", "bouncy": "playful",
    "sexy": "sexy / groovy", "sensual": "sexy / groovy", "groovy": "sexy / groovy", "seductive": "sexy / groovy",
    # Not moods, but Last.fm's tags say them often and reliably (track-level tags only)
    "instrumental": "instrumental", "female vocalists": "female vocal", "female vocalist": "female vocal",
    "female vocals": "female vocal", "male vocalists": "male vocal", "male vocals": "male vocal", "rap": "rap",
    "spoken word": "spoken word", "vocal chops": "vocal chops", "piano": "piano", "saxophone": "brass / sax",
    "guitar": "guitar", "acid": "acid (303)", "lo-fi": "lo-fi / dusty", "lofi": "lo-fi / dusty",
    "live": "raw / live",
}
LASTFM_GENRE_EXTRA = {"drum and bass": "Drum & Bass", "dnb": "Drum & Bass", "kpop": "K-pop", "k-pop": "K-pop",
                      "electropop": "Synth-pop", "hip-hop": "@Hip Hop & R&B", "rnb": "R&B / Soul", "edm": None,
                      "garage": "UK Garage", "2-step": "UK Garage", "big room": "Big Room / Mainstage"}

# Main genre → likely beat pattern (and feel). Applied only once the genre is confirmed; weight 1.0 auto-confirms.
GENRE_BEAT = {
    **{g: {"four-on-the-floor": 1.0} for g in GENRES["House"]},
    **{g: {"four-on-the-floor": 1.0} for g in GENRES["Techno"]},
    **{g: {"four-on-the-floor": 1.0} for g in GENRES["Trance"]},
    "Speed Garage": {"four-on-the-floor": 1.0}, "Bassline": {"four-on-the-floor": 1.0},
    "Big Room / Mainstage": {"four-on-the-floor": 1.0}, "Hardstyle": {"four-on-the-floor": 1.0},
    "UK Garage": {"2-step": 0.6, "four-on-the-floor": 0.4}, "Drum & Bass": {"dnb breaks": 1.0},
    "Dubstep": {"half-time": 0.8}, "Halftime": {"half-time": 0.8}, "EDM Trap": {"trap beat": 0.6, "half-time": 0.4},
    "Future Bass": {"half-time": 0.6}, "Trap": {"trap beat": 0.8}, "Cloud Rap": {"trap beat": 0.6},
    "Breaks": {"breakbeat": 0.9}, "Grime": {"breakbeat": 0.5, "half-time": 0.3}, "Latin / Reggaeton": {"dembow": 0.6},
    "Alt / Indie Rock": {"live backbeat": 0.8}, "Pop Rock": {"live backbeat": 0.8}, "Punk / Hard Rock": {"live backbeat": 0.9},
    "Dance Pop": {"four-on-the-floor": 0.6},
}
GENRE_FEEL = {
    "Speed Garage": {"swung / shuffled": 0.8, "off-beat bass": 0.8}, "UK Garage": {"swung / shuffled": 0.8},
    "Bassline": {"off-beat bass": 0.6}, "Afro House": {"polyrhythmic": 0.6, "syncopated / percussive": 0.6},
    "Tech House": {"syncopated / percussive": 0.4}, "Peak-time / Hard Techno": {"driving / straight": 0.7},
}

# Search: extra phrases that mean a tag. Tag names, and each part of a "a / b" name, match by themselves.
SEARCH_ALIASES = {
    "4x4": "four-on-the-floor", "four on the floor": "four-on-the-floor", "2 step": "2-step", "2step": "2-step",
    "two step": "2-step", "garage": "UK Garage", "ukg": "UK Garage", "dnb": "Drum & Bass", "d&b": "Drum & Bass",
    "drum and bass": "Drum & Bass", "breakbeat": "breakbeat", "half time": "half-time", "trap beat": "trap beat",
    "shuffle": "swung / shuffled", "swing": "swung / shuffled", "offbeat bass": "off-beat bass", "offbeat hats": "off-beat hats",
    "peak time": "Peak-time / Hard Techno", "melodic techno": "Melodic House & Techno", "melodic house": "Melodic House & Techno",
    "edm trap": "EDM Trap", "kpop": "K-pop", "rnb": "R&B / Soul", "reggaeton": "Latin / Reggaeton", "ambient": "Downtempo / Ambient",
    "chill": "dreamy / chill", "sad": "melancholic", "happy": "euphoric", "uplifting": "euphoric", "angry": "aggressive",
    "groovy": "sexy / groovy", "sexy": "sexy / groovy", "fun": "playful",
}


def search_phrases() -> dict[str, str]:
    """Lowercased phrase → tag, for matching tags inside a search query."""
    out = {}
    for _, (tags, _) in AXES.items():
        for name in tags:
            out[name.lower()] = name
            for part in name.split(" / "):
                if len(part) >= 3:
                    out.setdefault(part.lower(), name)
    return out | SEARCH_ALIASES
