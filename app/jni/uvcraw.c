// Minimal UVC frame reader on top of Linux usbfs.
// Java claims the interface, negotiates probe/commit and selects the alt setting;
// this file only streams URBs (isochronous or bulk) and reassembles frames.
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

// Many small URBs: phones often cannot allocate large contiguous kernel buffers (ENOMEM).
// 24 URBs x 8 packets x 3072 B = 24 KB each; on ENOMEM the packets per URB halve down to 1.
#define NURB 24
#define NPKT_MAX 8

static int g_fd = -1, g_iso, g_pkt, g_fsize, g_inflight, g_npkt, g_nurb;
static volatile int g_stop;
static struct usbdevfs_urb *g_urb[NURB];
static unsigned char *g_frame, *g_ready;
static int g_len, g_fid = -1, g_bad, g_have;

static void free_urb(struct usbdevfs_urb *u);

static void finish(void) {
    if (!g_bad && g_len == g_fsize) {
        memcpy(g_ready, g_frame, g_fsize);
        g_have = 1;
    }
    g_len = 0;
    g_bad = 0;
}

// One UVC payload: header (length, flags) followed by image bytes.
static void payload(const unsigned char *p, int n) {
    if (n < 2) return;
    int h = p[0];
    if (h < 2 || h > n) return;
    int fl = p[1], fid = fl & 0x01;
    if (g_fid >= 0 && fid != g_fid && g_len > 0) finish();
    g_fid = fid;
    if (fl & 0x40) g_bad = 1;
    int d = n - h;
    if (g_len + d <= g_fsize) {
        memcpy(g_frame + g_len, p + h, d);
        g_len += d;
    } else {
        g_bad = 1;
    }
    if (fl & 0x02) finish();
}

static void release(void) {
    for (int i = 0; i < NURB; i++) { free_urb(g_urb[i]); g_urb[i] = NULL; }
    free(g_frame);
    free(g_ready);
    g_frame = g_ready = NULL;
    g_fd = -1;
}

static struct usbdevfs_urb *make_urb(int ep, int npkt) {
    struct usbdevfs_urb *u;
    if (g_iso) {
        u = calloc(1, sizeof(*u) + npkt * sizeof(struct usbdevfs_iso_packet_desc));
        u->type = USBDEVFS_URB_TYPE_ISO;
        u->flags = USBDEVFS_URB_ISO_ASAP;
        u->number_of_packets = npkt;
        u->buffer_length = npkt * g_pkt;
        for (int k = 0; k < npkt; k++) u->iso_frame_desc[k].length = g_pkt;
    } else {
        u = calloc(1, sizeof(*u));
        u->type = USBDEVFS_URB_TYPE_BULK;
        u->buffer_length = g_pkt;
    }
    u->endpoint = ep;
    u->buffer = malloc(u->buffer_length);
    return u;
}

static void free_urb(struct usbdevfs_urb *u) { if (u) { free(u->buffer); free(u); } }

/** Cancels and reaps everything submitted so far, then frees it (used on a failed start). */
static void abort_all(void) {
    for (int i = 0; i < g_nurb; i++) if (g_urb[i]) ioctl(g_fd, USBDEVFS_DISCARDURB, g_urb[i]);
    while (g_inflight > 0) {
        struct usbdevfs_urb *u;
        if (ioctl(g_fd, USBDEVFS_REAPURB, &u)) break;
        g_inflight--;
    }
    for (int i = 0; i < NURB; i++) { free_urb(g_urb[i]); g_urb[i] = NULL; }
    free(g_frame); free(g_ready);
    g_frame = g_ready = NULL;
    g_fd = -1;
}

/** Returns 0, or -errno; on success *npkt_out / *nurb_out tell what the kernel accepted. */
JNIEXPORT jint JNICALL
Java_app_emberglass_Uvc_start(JNIEnv *e, jclass c, jint fd, jint ep, jint iso, jint pkt, jint fsize) {
    g_fd = fd; g_iso = iso; g_pkt = pkt; g_fsize = fsize;
    g_stop = 0; g_inflight = 0; g_len = 0; g_fid = -1; g_bad = 0; g_have = 0; g_nurb = 0;
    memset(g_urb, 0, sizeof(g_urb));
    g_frame = malloc(fsize);
    g_ready = malloc(fsize);
    // first URB: find a packet count the kernel accepts
    int err = 0;
    for (g_npkt = iso ? NPKT_MAX : 1; g_npkt >= 1; g_npkt /= 2) {
        struct usbdevfs_urb *u = make_urb(ep, g_npkt);
        if (ioctl(fd, USBDEVFS_SUBMITURB, u) == 0) { g_urb[0] = u; g_inflight = 1; g_nurb = 1; break; }
        err = errno;
        free_urb(u);
        if (err != ENOMEM || !iso) break;
    }
    if (g_nurb == 0) { abort_all(); return -err; }
    // the rest: stop adding when the kernel runs out, two in flight are enough to stream
    for (int i = 1; i < NURB; i++) {
        struct usbdevfs_urb *u = make_urb(ep, g_npkt);
        if (ioctl(fd, USBDEVFS_SUBMITURB, u)) {
            err = errno;
            free_urb(u);
            if (err == ENOMEM && g_nurb >= 2) break;
            abort_all();
            return -err;
        }
        g_urb[i] = u;
        g_inflight++;
        g_nurb++;
    }
    return 0;
}

/** URBs in flight and packets per URB, for the status line. */
JNIEXPORT jint JNICALL
Java_app_emberglass_Uvc_layout(JNIEnv *e, jclass c) { return g_nurb * 100 + g_npkt; }

// Blocks until one complete frame is copied into the direct buffer. Returns its size, or <0.
JNIEXPORT jint JNICALL
Java_app_emberglass_Uvc_read(JNIEnv *e, jclass c, jobject buf) {
    unsigned char *dst = (*e)->GetDirectBufferAddress(e, buf);
    while (!g_have) {
        struct usbdevfs_urb *u;
        if (ioctl(g_fd, USBDEVFS_REAPURB, &u)) {
            if (errno == EINTR) continue;
            return -errno;  // device gone: URBs are already dead, buffers are leaked on purpose
        }
        g_inflight--;
        if (g_stop) {
            if (g_inflight == 0) release();
            if (g_fd < 0) return -1;
            continue;
        }
        if (u->status == -ENODEV || u->status == -ESHUTDOWN) return u->status;
        if (g_iso) {
            for (int k = 0; k < u->number_of_packets; k++) {
                struct usbdevfs_iso_packet_desc *d = &u->iso_frame_desc[k];
                if (d->status == 0) payload((unsigned char *) u->buffer + k * g_pkt, d->actual_length);
            }
        } else if (u->status == 0) {
            payload(u->buffer, u->actual_length);
        }
        u->status = 0;
        u->actual_length = 0;
        if (ioctl(g_fd, USBDEVFS_SUBMITURB, u)) return -errno;
        g_inflight++;
    }
    memcpy(dst, g_ready, g_fsize);
    g_have = 0;
    return g_fsize;
}

// Called from another thread: cancels the URBs; the reader thread frees them and returns -1.
JNIEXPORT void JNICALL
Java_app_emberglass_Uvc_stop(JNIEnv *e, jclass c) {
    if (g_fd < 0) return;
    g_stop = 1;
    for (int i = 0; i < g_nurb; i++)
        if (g_urb[i]) ioctl(g_fd, USBDEVFS_DISCARDURB, g_urb[i]);
}
