#include "esp_log.h"
#include "esp_lvgl_port.h"
#include <lvgl.h>
#include <math.h>
#include <string.h>

#define BACKGROUND_COLOR lv_color_hex(0x101200)
#define COLOR lv_color_hex(0xa4b700)
#define DEVELOP 0

void init_ui();
void set_bluetooth_state(bool enabled);
void set_current_music(char *current_music);
void set_music_cover(uint8_t *music_cover_map_src);
void set_rpm(uint16_t rpm);
