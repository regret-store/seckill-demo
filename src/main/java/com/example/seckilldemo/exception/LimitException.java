package com.example.seckilldemo.exception;

public class LimitException extends RuntimeException{
    public LimitException(String message){
        super(message);
    }
}
