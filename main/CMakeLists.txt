idf_component_register(
    SRCS "lvgl_port.c" "main.c" "twingo_logo.c" "waveshare_rgb_lcd_port.c" "waveshare_twai_port.c"
    INCLUDE_DIRS ".")

idf_component_get_property(lvgl_lib lvgl__lvgl COMPONENT_LIB)
target_compile_options(${lvgl_lib} PRIVATE -Wno-format)
