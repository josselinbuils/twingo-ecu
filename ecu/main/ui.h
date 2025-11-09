#ifndef UI_H
#define UI_H

#include "esp_check.h"
#include "esp_log.h"
#include "esp_lvgl_port.h"
#include "lcd.h"
#include <lvgl.h>
#include <math.h>
#include <string.h>

#define BACKGROUND_COLOR lv_color_hex(0x101200)
#define COLOR lv_color_hex(0xa4b700)
#define DEVELOP 0

void ui_init(void (*twingo_click_callback)());
void ui_set_bluetooth_state(bool enabled);
void ui_set_current_music(char *current_music);
void ui_set_music_cover(uint8_t *music_cover_map_src);
void ui_set_rpm(uint16_t rpm);

#endif
