package com.aiolos.plaza.order.chain;

public interface Chain<T> {
    void proceed(T context);
}