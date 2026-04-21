package com.nechaev.mapper;

import com.nechaev.dto.AnswerResponse;
import com.nechaev.dto.QuestionRequest;
import com.nechaev.model.Answer;
import com.nechaev.model.Question;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ChatMapper {

    @Mapping(source = "question", target = "text")
    Question toQuestion(QuestionRequest request);

    @Mapping(source = "text", target = "answer")
    AnswerResponse toResponse(Answer answer);
}
