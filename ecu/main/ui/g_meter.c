#include <lvgl.h>
#include "colors.h"

const int G_METER_LINE_COUNT = 3;
const int G_METER_OPA = LV_OPA_50;
const int G_METER_SIZE = 240;
const int G_METER_Y_OFFSET = 40;

void ui_draw_g_meter(lv_obj_t *screen) {
    for (int i = 1; i <= G_METER_LINE_COUNT; i++) {
        lv_obj_t *arc = lv_arc_create(screen);
        lv_arc_set_bg_angles(arc, 0, 360);
        lv_obj_remove_style(arc, NULL, LV_PART_INDICATOR);
        lv_obj_remove_style(arc, NULL, LV_PART_KNOB);
        lv_obj_remove_flag(arc, LV_OBJ_FLAG_CLICKABLE);
        lv_obj_align(arc, LV_ALIGN_CENTER, 0, G_METER_Y_OFFSET);
        lv_obj_set_style_arc_color(arc, COLOR, LV_PART_MAIN);
        lv_obj_set_style_arc_opa(arc, G_METER_OPA, LV_PART_MAIN);
        lv_obj_set_style_arc_width(arc, 3, LV_PART_MAIN);
        lv_obj_set_size(arc, G_METER_SIZE / G_METER_LINE_COUNT * i, G_METER_SIZE / G_METER_LINE_COUNT * i);
    }

    static lv_style_t style_line;
    lv_style_init(&style_line);
    lv_style_set_line_width(&style_line, 3);
    lv_style_set_line_color(&style_line, COLOR);
    lv_style_set_line_opa(&style_line, G_METER_OPA);

    static lv_point_precise_t horizontal_line_points[] = {{0, 0}, {G_METER_SIZE - 6, 0}};

    lv_obj_t *horizontal_line = lv_line_create(screen);
    lv_line_set_points(horizontal_line, horizontal_line_points, 2);
    lv_obj_add_style(horizontal_line, &style_line, 0);
    lv_obj_align(horizontal_line, LV_ALIGN_CENTER, 0, G_METER_Y_OFFSET);

    static lv_point_precise_t vertical_line_points[] = {{0, 0}, {0, G_METER_SIZE - 6}};

    lv_obj_t *vertical_line = lv_line_create(screen);
    lv_line_set_points(vertical_line, vertical_line_points, 2);
    lv_obj_add_style(vertical_line, &style_line, 0);
    lv_obj_align(vertical_line, LV_ALIGN_CENTER, 0, G_METER_Y_OFFSET);

    lv_obj_t *circle = lv_arc_create(screen);
    lv_arc_set_bg_angles(circle, 0, 360);
    lv_obj_remove_style(circle, NULL, LV_PART_INDICATOR);
    lv_obj_remove_style(circle, NULL, LV_PART_KNOB);
    lv_obj_remove_flag(circle, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_align(circle, LV_ALIGN_CENTER, 0 + 70, G_METER_Y_OFFSET + 50);
    lv_obj_set_style_arc_color(circle, COLOR, LV_PART_MAIN);
    lv_obj_set_style_arc_width(circle, 10, LV_PART_MAIN);
    lv_obj_set_size(circle, 20, 20);
}
