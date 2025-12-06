#ifndef UI_H
#define UI_H

void ui_init();

bool ui_lock();

void ui_set_bluetooth_state(bool enabled);

void ui_set_current_music(char *current_music);

void ui_set_music_cover(uint8_t *music_cover_map_src);

void ui_set_rpm(uint16_t rpm);

void ui_set_speed_limit(const char *speed_limit);

void ui_unlock();

#endif
