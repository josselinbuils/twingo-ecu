#ifndef UI_G_METER_H
#define UI_G_METER_H

#include <lvgl.h>

void ui_g_meter_draw(lv_obj_t *screen);

void ui_g_meter_hide();

void ui_g_meter_show();

void ui_g_meter_update(int8_t x, int8_t y);

#endif
