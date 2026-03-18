package com.orbital.examples;

import com.orbital.api.Transducer;
import com.orbital.api.Transducer.TransducerException;
import com.orbital.api.Token;

import java.util.List;

/**
 * Example: XML tag transformation using transducers.
 */
public class XMLElementTransformation {

    public static void main(String[] args) {
        // Transducer to rename XML tags: <old> -> <new>
        Transducer renameTags = Transducer.compile("<old>:<new>");

        String input = "<old>Hello</old>";
        String result = renameTags.applyUp(input);

        System.out.println("Input: " + input);
        System.out.println("Output: " + result);

        // Transducer to uppercase content
        Transducer uppercaseContent = Transducer.compile(">:<tag>:</tag>");

        String contentInput = "<tag>hello</tag>";
        String contentResult = uppercaseContent.applyUp(contentInput);

        System.out.println("Content Input: " + contentInput);
        System.out.println("Content Output: " + contentResult);

        // Transducer to rename attributes
        Transducer renameAttributes = Transducer.compile("name=:(id=)");

        String attrInput = "<tag name=\"test\"></tag>";
        String attrResult = renameAttributes.applyUp(attrInput);

        System.out.println("Attribute Input: " + attrInput);
        System.out.println("Attribute Output: " + attrResult);

        // Composing transformations
        Transducer composed = renameTags.compose(uppercaseContent);
        String composedResult = composed.applyUp("<old>hello</old>");

        System.out.println("Composed Input: <old>hello</old>");
        System.out.println("Composed Output: " + composedResult);

        // Tokenizing XML
        List<Token> tokens = renameTags.tokenize("<old>hello</old>");
        System.out.println("Tokens: " + tokens);
    }
}