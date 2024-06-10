package com.horstmann.violet.framework.util;

import java.util.Random;
import java.util.UUID;

public class UniqueIDGenerator
{
    public static synchronized String getNewId() {
        int leftLimit = 48;
        int rightLimit = 57;
        int targetStringLength = 4;
        Random random = new Random();

        return random.ints(leftLimit, rightLimit + 1)
                .limit(targetStringLength)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }
}