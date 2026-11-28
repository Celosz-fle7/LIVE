use jni::objects::{JByteArray, JClass, JObjectArray, JString};
use jni::sys::{jobjectArray, jstring};
use jni::JNIEnv;

pub fn ping_message() -> &'static str {
    "live_core_rust_ready"
}

/// Ekstrak URL HTTPS bersih dari payload biner (mis. respons Protobuf),
/// berhenti pada delimiter kontrol biner (< 0x20 / spasi / tanda kutip).
pub fn extract_https_urls(raw: &[u8]) -> Vec<String> {
    let mut urls = Vec::new();
    let needle = b"https://";
    let mut i = 0;

    while i + needle.len() <= raw.len() {
        if &raw[i..i + needle.len()] == needle {
            let mut end = i + needle.len();
            while end < raw.len() {
                let b = raw[end];
                if is_url_byte(b) {
                    end += 1;
                } else {
                    break;
                }
            }
            if end > i + needle.len() + 4 {
                if let Ok(s) = std::str::from_utf8(&raw[i..end]) {
                    let candidate = s.to_string();
                    if !urls.contains(&candidate) {
                        urls.push(candidate);
                    }
                }
            }
            i = end;
        } else {
            i += 1;
        }
    }
    urls
}

#[inline]
fn is_url_byte(b: u8) -> bool {
    b.is_ascii_alphanumeric()
        || matches!(
            b,
            b'.' | b'-'
                | b'_'
                | b'/'
                | b'%'
                | b'?'
                | b'='
                | b'&'
                | b'~'
                | b'+'
                | b':'
                | b';'
                | b','
                | b'@'
                | b'#'
        )
}

pub fn xor_transform_in_place(buf: &mut [u8], key: &[u8], offset: usize) {
    if key.is_empty() {
        return;
    }
    for (idx, byte) in buf.iter_mut().enumerate() {
        *byte ^= key[(offset + idx) % key.len()];
    }
}

fn contains_bytes(haystack: &[u8], needle: &[u8]) -> bool {
    if needle.is_empty() || haystack.len() < needle.len() {
        return false;
    }
    haystack.windows(needle.len()).any(|w| w == needle)
}

fn contains_ascii_ci(haystack: &[u8], needle_lower: &[u8]) -> bool {
    if needle_lower.is_empty() || haystack.len() < needle_lower.len() {
        return false;
    }
    haystack.windows(needle_lower.len()).any(|w| {
        w.iter()
            .zip(needle_lower.iter())
            .all(|(&a, &b)| a.to_ascii_lowercase() == b)
    })
}

/// Deteksi kontainer media dari header biner atau manifest teks.
pub fn detect_container(raw: &[u8]) -> &'static str {
    if raw.len() >= 7 && contains_ascii_ci(raw, b"#extm3u") {
        return "hls";
    }
    if contains_ascii_ci(raw, b"<mpd") {
        return "dash";
    }
    if raw.len() >= 4 && raw[0] == 0x1A && raw[1] == 0x45 && raw[2] == 0xDF && raw[3] == 0xA3 {
        return "webm";
    }
    if raw.len() >= 12 && &raw[4..8] == b"ftyp" {
        return "mp4";
    }
    if contains_bytes(raw, b"moov") || contains_bytes(raw, b"stsd") {
        return "mp4";
    }
    if raw.len() >= 189 && raw[0] == 0x47 && raw[188] == 0x47 {
        return "ts";
    }
    "unknown"
}

/// Deteksi codec video (av01, vp9, hevc, avc1, vp8) dari header biner MP4/WebM
/// maupun teks manifest HLS/DASH.
pub fn detect_video_codec(raw: &[u8]) -> &'static str {
    if contains_bytes(raw, b"av01")
        || contains_bytes(raw, b"av1C")
        || contains_bytes(raw, b"V_AV1")
        || contains_ascii_ci(raw, b"av01.")
    {
        return "av01";
    }
    if contains_bytes(raw, b"vp09")
        || contains_bytes(raw, b"vpcC")
        || contains_bytes(raw, b"V_VP9")
        || contains_ascii_ci(raw, b"vp9")
    {
        return "vp9";
    }
    if contains_bytes(raw, b"hvc1")
        || contains_bytes(raw, b"hev1")
        || contains_bytes(raw, b"hvcC")
        || contains_bytes(raw, b"V_MPEGH/ISO/HEVC")
        || contains_ascii_ci(raw, b"hevc")
        || contains_ascii_ci(raw, b"h265")
    {
        return "hevc";
    }
    if contains_bytes(raw, b"avc1")
        || contains_bytes(raw, b"avc3")
        || contains_bytes(raw, b"avcC")
        || contains_bytes(raw, b"V_MPEG4/ISO/AVC")
        || contains_ascii_ci(raw, b"avc1.")
        || contains_ascii_ci(raw, b"h264")
    {
        return "avc1";
    }
    if contains_bytes(raw, b"vp08") || contains_bytes(raw, b"V_VP8") || contains_ascii_ci(raw, b"vp8") {
        return "vp8";
    }
    "unknown"
}

/// Deteksi codec audio dari header biner atau manifest teks.
pub fn detect_audio_codec(raw: &[u8]) -> &'static str {
    if contains_bytes(raw, b"Opus")
        || contains_bytes(raw, b"A_OPUS")
        || contains_ascii_ci(raw, b"opus")
    {
        return "opus";
    }
    if contains_bytes(raw, b"ec-3") || contains_bytes(raw, b"ac-3") {
        return "ac3";
    }
    if contains_bytes(raw, b"mp4a")
        || contains_bytes(raw, b"A_AAC")
        || contains_ascii_ci(raw, b"mp4a.")
    {
        return "aac";
    }
    if contains_bytes(raw, b"fLaC") || contains_bytes(raw, b"A_FLAC") {
        return "flac";
    }
    "unknown"
}

/// Mengembalikan ringkasan probe dalam format `"<video>|<audio>|<container>"`.
pub fn probe_stream_summary(raw: &[u8]) -> String {
    let video = detect_video_codec(raw);
    let audio = detect_audio_codec(raw);
    let container = detect_container(raw);
    format!("{}|{}|{}", video, audio, container)
}

#[no_mangle]
pub extern "system" fn Java_com_liveStream_app_core_NativeCore_nativePing(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    env.new_string(ping_message())
        .expect("Failed to create JNI string")
        .into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_liveStream_app_core_NativeCore_nativeExtractCdnUrls(
    mut env: JNIEnv,
    _class: JClass,
    payload: JByteArray,
) -> jobjectArray {
    let bytes = env.convert_byte_array(payload).unwrap_or_default();
    let urls = extract_https_urls(&bytes);

    let string_class = env
        .find_class("java/lang/String")
        .expect("java/lang/String missing");
    let empty = env.new_string("").expect("empty string");
    let array: JObjectArray = env
        .new_object_array(urls.len() as i32, string_class, empty)
        .expect("Failed to allocate String[]");

    for (idx, url) in urls.iter().enumerate() {
        if let Ok(jstr) = env.new_string(url) {
            let _ = env.set_object_array_element(&array, idx as i32, jstr);
        }
    }
    array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_liveStream_app_core_NativeCore_nativeProbeCodec(
    env: JNIEnv,
    _class: JClass,
    payload: JByteArray,
) -> jstring {
    let bytes = env.convert_byte_array(payload).unwrap_or_default();
    let summary = probe_stream_summary(&bytes);
    env.new_string(summary)
        .expect("Failed to create JNI codec string")
        .into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_liveStream_app_core_NativeCore_nativeDetectCodecFromText(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
) -> jstring {
    let input: String = env
        .get_string(&text)
        .map(|s| s.into())
        .unwrap_or_default();
    let summary = probe_stream_summary(input.as_bytes());
    env.new_string(summary)
        .expect("Failed to create JNI codec text string")
        .into_raw()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_extract_https_urls_strips_protobuf_delimiters() {
        let payload = b"\x08\x00\x12\x20https://v3.tiktokcdn.com/video/a.mp4?a=845221\x18\x01\x12\x1chttps://v31-id-telin.tiktokcdn.com/b.mp4\x00";
        let urls = extract_https_urls(payload);
        assert_eq!(
            urls,
            vec![
                "https://v3.tiktokcdn.com/video/a.mp4?a=845221",
                "https://v31-id-telin.tiktokcdn.com/b.mp4"
            ]
        );
    }

    #[test]
    fn test_xor_transform_roundtrip() {
        let key = b"live";
        let orig = b"stream_payload_123";
        let mut buf = orig.to_vec();
        xor_transform_in_place(&mut buf, key, 2);
        assert_ne!(&buf[..], orig);
        xor_transform_in_place(&mut buf, key, 2);
        assert_eq!(&buf[..], orig);
    }

    #[test]
    fn test_probe_hls_av01_manifest() {
        let manifest = b"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1800000,CODECS=\"av01.0.08M.08,mp4a.40.2\"\nseg.m3u8";
        assert_eq!(probe_stream_summary(manifest), "av01|aac|hls");
    }

    #[test]
    fn test_probe_mp4_vp9_and_avc1() {
        let mp4_vp9 = b"\x00\x00\x00\x18ftypisom\x00\x00\x02\x00isom\x00\x00moov\x00stsd\x00vp09\x00Opus";
        assert_eq!(probe_stream_summary(mp4_vp9), "vp9|opus|mp4");

        let mp4_avc1 = b"\x00\x00\x00\x18ftypmp42\x00\x00\x00\x00mp42\x00\x00moov\x00avc1\x00mp4a";
        assert_eq!(probe_stream_summary(mp4_avc1), "avc1|aac|mp4");
    }

    #[test]
    fn test_probe_dash_hevc() {
        let dash = b"<MPD><AdaptationSet codecs=\"hvc1.1.6.L120.90\"/></MPD>";
        assert_eq!(probe_stream_summary(dash), "hevc|unknown|dash");
    }
}
