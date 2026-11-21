package com.ukulele.monitor;
/** Compile-time raw bindings: missing index or song fails the build. */
final class SongCatalogResources {
    static int id(String path){switch(path){
        case "songs/library.json":return R.raw.song_library_index;
        case "songs/twinkle_twinkle.txt":return R.raw.lib_song_twinkle_twinkle;
        case "songs/frere_jacques.txt":return R.raw.lib_song_frere_jacques;
        case "songs/ode_to_joy.txt":return R.raw.lib_song_ode_to_joy;
        case "songs/mary_little_lamb.txt":return R.raw.lib_song_mary_little_lamb;
        case "songs/london_bridge.txt":return R.raw.lib_song_london_bridge;
        case "songs/happy_birthday.txt":return R.raw.lib_song_happy_birthday;
        case "songs/jingle_bells.txt":return R.raw.lib_song_jingle_bells;
        case "songs/merry_christmas.txt":return R.raw.lib_song_merry_christmas;
        case "songs/yankee_doodle.txt":return R.raw.lib_song_yankee_doodle;
        case "songs/oh_susanna.txt":return R.raw.lib_song_oh_susanna;
        default:throw new IllegalArgumentException("未知曲目资源："+path);
    }}
}
