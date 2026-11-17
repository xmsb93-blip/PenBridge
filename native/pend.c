/* PenRelay daemon: grab a real pen evdev node, mirror it to a uinput stylus,
 * and allow the app to synthesize a clean stroke break (UP + DOWN). */
#define _GNU_SOURCE
#include <errno.h>
#include <stdarg.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/input.h>
#include <linux/uinput.h>

static int in_fd = -1;
static int ui_fd = -1;
static volatile int running = 1;

static int cur_x, cur_y, cur_p, cur_d = 1;
static int is_down;
static int breaking;
static long break_at;

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

static int bit_test(const unsigned char *b, int n) {
    return (b[n >> 3] >> (n & 7)) & 1;
}

/* 内核会把"值没变化"的 ABS 事件丢掉，所以位置/压力要直接从设备当前状态读一次 */
static void sync_state(void) {
    struct input_absinfo ai;
    if (ioctl(in_fd, EVIOCGABS(ABS_X), &ai) == 0) cur_x = ai.value;
    if (ioctl(in_fd, EVIOCGABS(ABS_Y), &ai) == 0) cur_y = ai.value;
    if (ioctl(in_fd, EVIOCGABS(ABS_PRESSURE), &ai) == 0) cur_p = ai.value;
}

static void out(const char *fmt, ...);

static void ui_write(int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = (unsigned short) type;
    ev.code = (unsigned short) code;
    ev.value = value;
    if (write(ui_fd, &ev, sizeof(ev)) < 0 && errno != EAGAIN) {
        /* ignore */
    }
}

/* write to uinput and report the same event to the app state machine */
static void ui_emit(int type, int code, int value) {
    ui_write(type, code, value);
    out("E %d %d %d\n", type, code, value);
}

static void out(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    fflush(stdout);
}

static int make_uinput(int src) {
    ui_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (ui_fd < 0) { out("ERR open uinput %d\n", errno); return -1; }

    unsigned char bits[(KEY_MAX + 8) / 8 + 1];
    memset(bits, 0, sizeof(bits));
    if (ioctl(src, EVIOCGBIT(EV_KEY, sizeof(bits)), bits) < 0) {
        out("ERR gbit key %d\n", errno); return -1;
    }
    ioctl(ui_fd, UI_SET_EVBIT, EV_KEY);
    for (int i = 0; i < KEY_MAX; i++)
        if (bit_test(bits, i)) ioctl(ui_fd, UI_SET_KEYBIT, i);

    unsigned char abits[(ABS_MAX + 8) / 8 + 1];
    memset(abits, 0, sizeof(abits));
    if (ioctl(src, EVIOCGBIT(EV_ABS, sizeof(abits)), abits) < 0) {
        out("ERR gbit abs %d\n", errno); return -1;
    }
    ioctl(ui_fd, UI_SET_EVBIT, EV_ABS);
    for (int i = 0; i <= ABS_MAX; i++) {
        if (!bit_test(abits, i)) continue;
        struct input_absinfo ai;
        memset(&ai, 0, sizeof(ai));
        if (ioctl(src, EVIOCGABS(i), &ai) < 0) continue;
        struct uinput_abs_setup as;
        memset(&as, 0, sizeof(as));
        as.code = (unsigned short) i;
        as.absinfo.minimum = ai.minimum;
        as.absinfo.maximum = ai.maximum;
        as.absinfo.fuzz = ai.fuzz;
        as.absinfo.flat = ai.flat;
        as.absinfo.resolution = ai.resolution;
        if (ioctl(ui_fd, UI_ABS_SETUP, &as) < 0) {
            out("ERR abs %d setup %d\n", i, errno);
        }
    }

    unsigned char props[(INPUT_PROP_MAX + 8) / 8 + 1];
    memset(props, 0, sizeof(props));
    if (ioctl(src, EVIOCGPROP(sizeof(props)), props) >= 0) {
        for (int i = 0; i <= INPUT_PROP_MAX; i++)
            if (bit_test(props, i)) ioctl(ui_fd, UI_SET_PROPBIT, i);
    }

    struct uinput_setup us;
    memset(&us, 0, sizeof(us));
    us.id.bustype = 0x001c;
    us.id.vendor = 0;
    us.id.product = 0;
    us.id.version = 0;
    strncpy(us.name, "FocusPen Relay", UINPUT_MAX_NAME_SIZE - 1);
    if (ioctl(ui_fd, UI_DEV_SETUP, &us) < 0) {
        out("ERR dev setup %d\n", errno); return -1;
    }
    if (ioctl(ui_fd, UI_DEV_CREATE) < 0) {
        out("ERR dev create %d\n", errno); return -1;
    }
    usleep(150000);
    return 0;
}

static void start_break(void) {
    if (!is_down || breaking) return;
    breaking = 1;
    ui_emit(EV_ABS, ABS_PRESSURE, 0);
    ui_emit(EV_ABS, ABS_DISTANCE, 1);
    ui_emit(EV_KEY, BTN_TOUCH, 0);
    ui_emit(EV_SYN, SYN_REPORT, 0);
    out("B UP\n");
}

static void finish_break(void) {
    if (!breaking) return;
    if (!is_down) {
        breaking = 0;
        out("B ABORT\n");
        return;
    }
    breaking = 0;
    sync_state();
    ui_emit(EV_ABS, ABS_X, cur_x);
    ui_emit(EV_ABS, ABS_Y, cur_y);
    ui_emit(EV_ABS, ABS_DISTANCE, 0);
    ui_emit(EV_ABS, ABS_PRESSURE, cur_p > 1200 ? cur_p : 3000);
    ui_emit(EV_KEY, BTN_TOOL_PEN, 1);
    ui_emit(EV_KEY, BTN_TOUCH, 1);
    ui_emit(EV_SYN, SYN_REPORT, 0);
    out("B DOWN x=%d y=%d p=%d\n", cur_x, cur_y, cur_p);
}

static void handle_event(const struct input_event *ev) {
    if (ev->type == EV_ABS) {
        if (ev->code == ABS_X) { if (is_down) cur_x = ev->value; }
        else if (ev->code == ABS_Y) { if (is_down) cur_y = ev->value; }
        else if (ev->code == ABS_PRESSURE) cur_p = ev->value;
        else if (ev->code == ABS_DISTANCE) cur_d = ev->value;
    } else if (ev->type == EV_KEY) {
        if (ev->code == BTN_TOUCH) {
            if (ev->value == 0) {
                is_down = 0;
                if (breaking) {
                    /* pen lifted during the gap: drop the pending down */
                    breaking = 0;
                    out("B ABORT\n");
                }
            } else if (ev->value == 1) {
                is_down = 1;
            }
        }
    }
}

int main(int argc, char **argv) {
    if (argc < 2) { out("ERR usage: pend <event-node>\n"); return 2; }
    setvbuf(stdout, NULL, _IOLBF, 0);

    in_fd = open(argv[1], O_RDONLY | O_NONBLOCK);
    if (in_fd < 0) { out("ERR open %s %d\n", argv[1], errno); return 1; }
    if (make_uinput(in_fd) < 0) return 1;
    if (ioctl(in_fd, EVIOCGRAB, 1) < 0) {
        out("ERR grab %d\n", errno);
        return 1;
    }
    sync_state();
    out("READY %s\n", argv[1]);

    char line[64];
    int line_len = 0;

    while (running) {
        struct pollfd pfds[2];
        pfds[0].fd = 0;
        pfds[0].events = POLLIN;
        pfds[0].revents = 0;
        pfds[1].fd = in_fd;
        pfds[1].events = POLLIN;
        pfds[1].revents = 0;

        int timeout = -1;
        if (breaking) {
            long left = break_at - now_ms();
            timeout = left <= 0 ? 0 : (int) left;
        }
        int r = poll(pfds, 2, timeout);
        if (r < 0 && errno != EINTR) { out("ERR poll %d\n", errno); break; }

        if (pfds[0].revents & (POLLIN | POLLHUP)) {
            char buf[128];
            ssize_t n = read(0, buf, sizeof(buf));
            if (n <= 0) { running = 0; break; }
            for (ssize_t i = 0; i < n; i++) {
                if (buf[i] == '\n') {
                    line[line_len] = 0;
                    line_len = 0;
                    if (strcmp(line, "up") == 0) {
                        start_break();
                        break_at = now_ms() + 900;
                    } else if (strcmp(line, "down") == 0) {
                        finish_break();
                        break_at = 0;
                    } else if (strcmp(line, "break") == 0) {
                        start_break();
                        break_at = now_ms() + 55;
                    }
                    else if (strcmp(line, "quit") == 0) running = 0;
                    else if (strcmp(line, "ping") == 0) out("PONG\n");
                } else if (line_len < (int) sizeof(line) - 1) {
                    line[line_len++] = buf[i];
                }
            }
        }

        if (pfds[1].revents & POLLIN) {
            struct input_event evs[64];
            ssize_t n = read(in_fd, evs, sizeof(evs));
            if (n < 0 && errno != EAGAIN) { out("ERR read %d\n", errno); break; }
            for (ssize_t i = 0; i + (ssize_t) sizeof(struct input_event) <= n; i += sizeof(struct input_event)) {
                struct input_event *ev = (struct input_event *) ((char *) evs + i);
                handle_event(ev);
                if (breaking) continue;
                ui_write(ev->type, ev->code, ev->value);
                out("E %d %d %d\n", ev->type, ev->code, ev->value);
            }
        }

        if (breaking && break_at && now_ms() >= break_at) {
            out("B TIMEOUT\n");
            finish_break();
            break_at = 0;
        }
    }

    out("EXIT\n");
    ioctl(in_fd, EVIOCGRAB, 0);
    if (ui_fd >= 0) { ioctl(ui_fd, UI_DEV_DESTROY); close(ui_fd); }
    close(in_fd);
    return 0;
}
