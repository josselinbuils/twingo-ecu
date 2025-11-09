#include "ui.h"
#include "twingo_logo.c"

#define TAG "UI"

extern esp_lcd_panel_handle_t lcd_panel;
extern esp_lcd_touch_handle_t touch_handle;

const int BORDER_WIDTH = 5;
const int INDICATOR_ANGLE_RANGE = 194;
const int INDICATOR_RANGE = 6000;
const int INDICATOR_ROTATION = 173;
const int INDICATOR_POSITION_Y = 210;
const int INDICATOR_WIDTH = 50;
const int LABELS_GAP = 30;
const int PADDING = 10;
const int SCALE_SIZE = 980;
const int TICK_LENGTH = INDICATOR_WIDTH + PADDING * 2 - 2;
const int TICK_WIDTH = 2;

lv_display_t *lvgl_disp = NULL;
lv_indev_t *lvgl_touch_indev = NULL;
lv_obj_t *bluetooth_label;
lv_obj_t *cover_img;
lv_obj_t *indic;
lv_obj_t *logo_img;
lv_obj_t *music_artist_label;
lv_obj_t *music_title_label;

char music_artist[100];
char music_title[100];

esp_err_t ui_lvgl_init(void);

void ui_init(lv_event_cb_t twingo_click_callback) {
  ui_lvgl_init();

  vTaskDelay(1); // Prevent LVGL slow boot

  if (lvgl_port_lock(-1)) {
    lv_obj_set_style_bg_color(lv_screen_active(), BACKGROUND_COLOR, LV_PART_MAIN);
    lv_obj_clear_flag(lv_screen_active(), LV_OBJ_FLAG_SCROLLABLE);

    // Arc indicator

    indic = lv_arc_create(lv_screen_active());

    lv_obj_remove_style(indic, NULL, LV_PART_KNOB);
    lv_obj_align(indic, LV_ALIGN_CENTER, 0, INDICATOR_POSITION_Y);
    lv_obj_set_size(indic, SCALE_SIZE, SCALE_SIZE);
    lv_obj_set_style_arc_opa(indic, 0, LV_PART_MAIN);
    lv_obj_set_style_arc_color(indic, COLOR, LV_PART_INDICATOR);
    lv_obj_set_style_arc_width(indic, INDICATOR_WIDTH, LV_PART_MAIN);
    lv_obj_set_style_arc_width(indic, INDICATOR_WIDTH, LV_PART_INDICATOR);
    lv_obj_set_style_radius(indic, 0, LV_PART_MAIN);
    lv_obj_set_style_radius(indic, 0, LV_PART_INDICATOR);
    lv_arc_set_rotation(indic, INDICATOR_ROTATION);
    lv_arc_set_bg_angles(indic, 0, INDICATOR_ANGLE_RANGE);
    lv_arc_set_range(indic, 0, INDICATOR_RANGE);
    lv_obj_set_style_pad_all(indic, PADDING + BORDER_WIDTH, LV_PART_MAIN);

    static lv_style_t indic_style;

    lv_style_init(&indic_style);
    lv_style_set_arc_rounded(&indic_style, false);
    lv_obj_add_style(indic, &indic_style, LV_PART_INDICATOR);

    // Scales

    lv_obj_t *inner_scale = lv_scale_create(lv_screen_active());
    lv_obj_t *outer_scale = lv_scale_create(lv_screen_active());

    lv_obj_clear_flag(inner_scale, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_clear_flag(outer_scale, LV_OBJ_FLAG_SCROLLABLE);

    lv_scale_set_mode(inner_scale, LV_SCALE_MODE_ROUND_INNER);
    lv_scale_set_mode(outer_scale, LV_SCALE_MODE_ROUND_OUTER);

    lv_obj_align(inner_scale, LV_ALIGN_CENTER, 0, INDICATOR_POSITION_Y);
    lv_obj_align(outer_scale, LV_ALIGN_CENTER, 0, INDICATOR_POSITION_Y);

    lv_obj_set_size(inner_scale, SCALE_SIZE, SCALE_SIZE);
    lv_obj_set_size(outer_scale, SCALE_SIZE, SCALE_SIZE);

    lv_scale_set_angle_range(inner_scale, INDICATOR_ANGLE_RANGE);
    lv_scale_set_angle_range(outer_scale, INDICATOR_ANGLE_RANGE);

    lv_scale_set_rotation(inner_scale, INDICATOR_ROTATION);
    lv_scale_set_rotation(outer_scale, INDICATOR_ROTATION);

    static lv_style_t main_line_style;

    lv_style_init(&main_line_style);
    lv_style_set_bg_color(&main_line_style, BACKGROUND_COLOR);
    lv_style_set_arc_color(&main_line_style, COLOR);
    lv_style_set_arc_width(&main_line_style, BORDER_WIDTH);

    lv_obj_add_style(inner_scale, &main_line_style, LV_PART_MAIN);
    lv_obj_add_style(outer_scale, &main_line_style, LV_PART_MAIN);

    // Inner scale

    lv_scale_set_range(inner_scale, 0, INDICATOR_RANGE);
    lv_scale_set_total_tick_count(inner_scale, 151);
    lv_scale_set_major_tick_every(inner_scale, 25);

    lv_obj_set_style_length(inner_scale, TICK_LENGTH, LV_PART_ITEMS);
    lv_obj_set_style_length(inner_scale, TICK_LENGTH, LV_PART_INDICATOR);

    static lv_style_t tick_style;

    lv_style_init(&tick_style);
    lv_style_set_line_width(&tick_style, TICK_WIDTH);
    lv_style_set_line_color(&tick_style, BACKGROUND_COLOR);
    lv_style_set_line_width(&tick_style, TICK_WIDTH);
    lv_style_set_text_color(&tick_style, COLOR);
    lv_style_set_text_font(&tick_style, &lv_font_montserrat_48);

    lv_scale_section_t *main_section = lv_scale_add_section(inner_scale);

    lv_scale_section_set_range(main_section, 0, INDICATOR_RANGE);
    lv_scale_set_section_style_items(inner_scale, main_section, &tick_style);
    lv_scale_set_section_style_indicator(inner_scale, main_section, &tick_style);

    static const char *labels[8] = {"0", "1", "2", "3", "4", "5", "6", NULL};

    lv_scale_set_label_show(inner_scale, true);
    lv_scale_set_text_src(inner_scale, labels);
    lv_obj_set_style_pad_radial(inner_scale, LABELS_GAP, LV_PART_INDICATOR);

    // Outer scale

    lv_scale_set_label_show(outer_scale, false);
    lv_scale_set_total_tick_count(outer_scale, 2);
    lv_obj_set_style_length(outer_scale, 0, LV_PART_ITEMS);
    lv_obj_set_style_pad_all(
      outer_scale, BORDER_WIDTH + PADDING + INDICATOR_WIDTH + PADDING, LV_PART_MAIN
    );

    // End lines

    static lv_style_t line_style;

    lv_style_init(&line_style);
    lv_style_set_line_width(&line_style, BORDER_WIDTH);
    lv_style_set_line_color(&line_style, COLOR);

    const int end_line_width = INDICATOR_WIDTH + PADDING * 2 + BORDER_WIDTH * 2 - 2;

    static lv_point_precise_t end_line_points[] = {
      {0, 0},
      {1, 2},
      {2, 4},
      {4, 6},
      {6, 8},
      {8, 10},
      {end_line_width - 8, 10},
      {end_line_width - 6, 8},
      {end_line_width - 4, 6},
      {end_line_width - 2, 4},
      {end_line_width - 1, 2},
      {end_line_width, 0}
    };
    static lv_point_precise_t end_line_points_left[12];
    static lv_point_precise_t end_line_points_right[12];

    double angle_rad = 7.0 * M_PI / 180.0;
    double x;
    double y;

    for (int i = 0; i < 12; i++) {
      x = end_line_points[i].x;
      y = end_line_points[i].y;

      end_line_points_left[i].x = x * cos(angle_rad) + y * sin(angle_rad);
      end_line_points_left[i].y = -x * sin(angle_rad) + y * cos(angle_rad);

      end_line_points_right[i].x = x * cos(angle_rad) - y * sin(angle_rad);
      end_line_points_right[i].y = x * sin(angle_rad) + y * cos(angle_rad);
    }

    lv_obj_t *end_line_left = lv_line_create(inner_scale);

    lv_line_set_points(end_line_left, end_line_points_left, 12);
    lv_obj_add_style(end_line_left, &line_style, 0);
    lv_obj_align(end_line_left, LV_ALIGN_LEFT_MID, 4, 58);

    lv_obj_t *end_line_right = lv_line_create(inner_scale);

    lv_line_set_points(end_line_right, end_line_points_right, 12);
    lv_obj_add_style(end_line_right, &line_style, 0);
    lv_obj_align(end_line_right, LV_ALIGN_RIGHT_MID, -4, 58);

    // Add twingo logo

    LV_IMG_DECLARE(twingo_logo)
    logo_img = lv_imagebutton_create(inner_scale);

    lv_imagebutton_set_src(logo_img, LV_IMAGEBUTTON_STATE_RELEASED, NULL, &twingo_logo, NULL);
    lv_obj_set_style_img_recolor_opa(logo_img, LV_OPA_100, 0);
    lv_obj_set_style_img_recolor(logo_img, COLOR, 0);
    lv_obj_align(logo_img, LV_ALIGN_CENTER, 0, -170);
    lv_obj_remove_flag(logo_img, LV_OBJ_FLAG_PRESS_LOCK);
    lv_obj_add_event_cb(logo_img, twingo_click_callback, LV_EVENT_ALL, NULL);

    // Add current music

    static lv_style_t label_style;

    lv_style_init(&label_style);
    lv_style_set_text_color(&label_style, COLOR);
    lv_style_set_text_font(&label_style, &lv_font_montserrat_28);

    music_title_label = lv_label_create(lv_screen_active());

    lv_obj_set_height(music_title_label, 30);
    lv_label_set_long_mode(music_title_label, LV_LABEL_LONG_MODE_DOTS);
    lv_obj_add_style(music_title_label, &label_style, 0);
    lv_label_set_text(music_title_label, "");

    music_artist_label = lv_label_create(lv_screen_active());

    lv_obj_set_height(music_artist_label, 30);
    lv_label_set_long_mode(music_artist_label, LV_LABEL_LONG_MODE_DOTS);
    lv_obj_add_style(music_artist_label, &label_style, 0);
    lv_obj_set_style_transform_scale(music_artist_label, 80 * 255 / 100, LV_PART_MAIN);
    lv_label_set_text(music_artist_label, "");

    // Add cover image

    cover_img = lv_img_create(inner_scale);

    lv_obj_set_style_img_recolor(cover_img, COLOR, 0);

    // Add bluetooth label

    bluetooth_label = lv_label_create(lv_screen_active());

    lv_obj_align(bluetooth_label, LV_ALIGN_TOP_LEFT, 20, 20);
    lv_obj_add_style(bluetooth_label, &label_style, 0);
    lv_label_set_text(bluetooth_label, "");

    if (DEVELOP) {
      lv_arc_set_value(indic, 5500);
    }

    lvgl_port_unlock();
  }
}

esp_err_t ui_lvgl_init(void) {
  const lvgl_port_cfg_t lvgl_cfg = {
    .task_priority = 4, /* LVGL task priority */
    .task_stack = 6144, /* LVGL task stack size */
    .task_affinity = -1, /* LVGL task pinned to core (-1 is no affinity) */
    .task_max_sleep_ms = 500, /* Maximum sleep in LVGL task */
    .timer_period_ms = 2 /* LVGL timer tick period in ms */
  };
  ESP_RETURN_ON_ERROR(lvgl_port_init(&lvgl_cfg), TAG, "LVGL port initialization failed");

  uint32_t buff_size = LCD_H_RES * LCD_DRAW_BUFF_HEIGHT;
#if LCD_LVGL_FULL_REFRESH || LCD_LVGL_DIRECT_MODE
  buff_size = LCD_H_RES * LCD_V_RES;
#endif

  /* Add LCD screen */
  const lvgl_port_display_cfg_t disp_cfg = {
    .panel_handle = lcd_panel,
    .buffer_size = buff_size,
    .double_buffer = LCD_DRAW_BUFF_DOUBLE,
    .hres = LCD_H_RES,
    .vres = LCD_V_RES,
    .monochrome = false,
    .color_format = LV_COLOR_FORMAT_RGB565,
    .rotation =
      {
        .swap_xy = false,
        .mirror_x = false,
        .mirror_y = false,
      },
    .flags = {
      .buff_dma = false,
      .buff_spiram = false,
#if LCD_LVGL_FULL_REFRESH
      .full_refresh = true,
#elif LCD_LVGL_DIRECT_MODE
      .direct_mode = true,
#endif
      .swap_bytes = false,
    }
  };
  const lvgl_port_display_rgb_cfg_t rgb_cfg = {
    .flags = {
#if LCD_RGB_BOUNCE_BUFFER_MODE
      .bb_mode = true,
#else
      .bb_mode = false,
#endif
#if LCD_LVGL_AVOID_TEAR
      .avoid_tearing = true,
#else
      .avoid_tearing = false,
#endif
    }
  };
  lvgl_disp = lvgl_port_add_disp_rgb(&disp_cfg, &rgb_cfg);

  /* Add touch input (for selected screen) */
  const lvgl_port_touch_cfg_t touch_cfg = {
    .disp = lvgl_disp,
    .handle = touch_handle,
  };
  lvgl_touch_indev = lvgl_port_add_touch(&touch_cfg);

  return ESP_OK;
}

void ui_set_bluetooth_state(bool enabled) {
  if (lvgl_port_lock(-1)) {
    if (enabled) {
      lv_label_set_text(bluetooth_label, LV_SYMBOL_BLUETOOTH);
    } else {
      lv_label_set_text(bluetooth_label, "");
      ui_set_current_music("");
    }
    lvgl_port_unlock();
  }
}

void ui_set_current_music(char *current_music) {
  if (strlen(current_music) > 0) {
    strcpy(music_title, strtok(current_music, "\n"));
    strcpy(music_artist, strtok(NULL, "\n"));

    // We are going tow write in image buffer so we lock LVGL
    lvgl_port_lock(-1);
  } else if (lvgl_port_lock(-1)) {
    strcpy(music_title, "");
    strcpy(music_artist, "");

    lv_label_set_text(music_title_label, music_title);
    lv_label_set_text(music_artist_label, music_artist);
    lv_obj_add_flag(cover_img, LV_OBJ_FLAG_HIDDEN);
    lvgl_port_unlock();
  }
}

void ui_set_music_cover(uint8_t *music_cover_map) {
  ESP_LOGI(TAG, "Set current music: %s - %s", music_artist, music_title);

  const lv_draw_buf_t music_cover = {
    .header =
      {
        .magic = LV_IMAGE_HEADER_MAGIC,
        .cf = LV_COLOR_FORMAT_A8,
        .flags = LV_IMAGE_FLAGS_MODIFIABLE,
        .w = 64,
        .h = 64,
        .stride = 64,
        .reserved_2 = 0,
      },
    .data_size = 4096,
    .data = music_cover_map
  };

  lv_point_t label_size;

  int use_title_width = strlen(music_title) >= strlen(music_artist);

  lv_text_get_size(
    &label_size,
    use_title_width ? music_title : music_artist,
    &lv_font_montserrat_28,
    0,
    0,
    500,
    LV_TEXT_FLAG_NONE
  );

  int width = label_size.x + 30; // Margin to prevent unwanted crop
  int left = 55 + (use_title_width ? 0 : width * 0.1);

  lv_obj_set_width(music_title_label, width);
  lv_obj_set_width(music_artist_label, width);
  lv_obj_align(music_title_label, LV_ALIGN_BOTTOM_MID, left, -55);
  lv_obj_align(music_artist_label, LV_ALIGN_BOTTOM_MID, left, -21);
  lv_label_set_text(music_title_label, music_title);
  lv_label_set_text(music_artist_label, music_artist);
  lv_obj_clear_flag(cover_img, LV_OBJ_FLAG_HIDDEN);
  lv_img_set_src(cover_img, &music_cover);
  lv_obj_align_to(cover_img, music_title_label, LV_ALIGN_OUT_LEFT_MID, -15, 15);
  lvgl_port_unlock();
}

void ui_set_rpm(uint16_t rpm) {
  if (lvgl_port_lock(-1)) {
    lv_arc_set_value(indic, rpm);
    lvgl_port_unlock();
  }
}
