idf_component_register(
    SRCS "twingo_logo.c" "waveshare_rgb_lcd_port.c" "main.c" "lvgl_port.c"
    INCLUDE_DIRS ".")

idf_component_get_property(lvgl_lib lvgl__lvgl COMPONENT_LIB)
target_compile_options(${lvgl_lib} PRIVATE -Wno-format)
idf_build_set_property(COMPILE_OPTIONS "-DLV_FONT_MONTSERRAT_48=1" APPEND)
