cmake_minimum_required(VERSION 3.22.1)
project(khoirevanced_agent)

add_library(khoirevanced_agent SHARED agent.cpp)
find_library(log-lib log)
target_link_libraries(khoirevanced_agent ${log-lib})