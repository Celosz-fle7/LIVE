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
}
