package com.treasure_data.newclient.model.transform;

import java.io.IOException;
import java.io.InputStream;

public interface ResponseParser<M> {

    void parse(ResponseModelGen<M> init, InputStream in) throws IOException;

}