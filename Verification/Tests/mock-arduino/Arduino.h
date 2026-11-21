#pragma once
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <deque>
#include <vector>
#define SERIAL_8N1 0
class HardwareSerial {
public:
    std::deque<uint8_t> input;
    std::vector<uint8_t> output;
    void begin(uint32_t,int,int,int) {}
    int available(){return int(input.size());}
    int availableForWrite(){return 4096;}
    int read(){const int next=input.front();input.pop_front();return next;}
    size_t write(const uint8_t* bytes,size_t size){output.insert(output.end(),bytes,bytes+size);return size;}
    void println(const char* = nullptr) {}
    void printf(const char*, ...) {}
};
extern HardwareSerial Serial, Serial1;
uint32_t millis();
void delay(uint32_t duration);
