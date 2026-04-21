package com.nechaev.mapper;

import com.nechaev.dto.AnswerResponse;
import com.nechaev.dto.QuestionRequest;
import com.nechaev.model.Answer;
import com.nechaev.model.Question;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMapperTest {

    private final ChatMapper mapper = Mappers.getMapper(ChatMapper.class);

    @Test
    void toQuestionMapsQuestionFieldToText() {
        QuestionRequest request = new QuestionRequest("What is your experience?");

        Question question = mapper.toQuestion(request);

        assertThat(question.text()).isEqualTo("What is your experience?");
    }

    @Test
    void toResponseMapsTextFieldToAnswer() {
        Answer answer = new Answer("5 years of Java experience.");

        AnswerResponse response = mapper.toResponse(answer);

        assertThat(response.answer()).isEqualTo("5 years of Java experience.");
    }
}
