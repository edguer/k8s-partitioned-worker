package com.sample;

/**
 * Generic exception for any parition lock error.
 */
public class DistributedLockerException extends Exception {
    private static final long serialVersionUID = 1L;

    /**
     * Default constructor.
     * 
     * @param message        error message
     * @param innerException parent exception
     */
    public DistributedLockerException(String message) {
        super(message);
    }
}