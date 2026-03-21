package com.aiolos.plaza.order.chain;

public interface ChainHandler<T> {
    void handle(T context, Chain<T> chain);
}