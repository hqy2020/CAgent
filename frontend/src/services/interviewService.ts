import { api } from "./api";

// ===================== 类型定义 =====================

export interface InterviewCategory {
  id: string;
  name: string;
  description?: string | null;
  icon?: string | null;
  sortOrder: number;
  questionCount?: number;
  createTime?: string | null;
}

export interface InterviewQuestion {
  id: string;
  categoryId: string;
  question: string;
  answer: string;
  difficulty: number;
  tags?: string | null;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface InterviewQuestionListItem {
  id: string;
  categoryId: string;
  question: string;
  difficulty: number;
  tags?: string | null;
  createTime?: string | null;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

// ===================== 分类 API =====================

export const getInterviewCategories = async (): Promise<InterviewCategory[]> => {
  return api.get<InterviewCategory[], InterviewCategory[]>("/interview/categories");
};

export const createInterviewCategory = async (data: {
  name: string;
  description?: string;
  icon?: string;
  sortOrder?: number;
}): Promise<void> => {
  await api.post("/interview/categories", data);
};

export const updateInterviewCategory = async (data: {
  id: string;
  name?: string;
  description?: string;
  icon?: string;
  sortOrder?: number;
}): Promise<void> => {
  await api.put("/interview/categories", data);
};

export const deleteInterviewCategory = async (id: string): Promise<void> => {
  await api.delete(`/interview/categories/${id}`);
};

// ===================== 题目 API =====================

export const getInterviewQuestionsPage = async (
  pageNo = 1,
  pageSize = 20,
  categoryId?: string,
  difficulty?: number,
  keyword?: string
): Promise<PageResult<InterviewQuestionListItem>> => {
  return api.get<PageResult<InterviewQuestionListItem>, PageResult<InterviewQuestionListItem>>(
    "/interview/questions",
    {
      params: {
        pageNo,
        pageSize,
        categoryId: categoryId || undefined,
        difficulty: difficulty || undefined,
        keyword: keyword || undefined
      }
    }
  );
};

export const getInterviewQuestion = async (id: string): Promise<InterviewQuestion> => {
  return api.get<InterviewQuestion, InterviewQuestion>(`/interview/questions/${id}`);
};

export const createInterviewQuestion = async (data: {
  categoryId: string;
  question: string;
  answer: string;
  difficulty: number;
  tags?: string;
}): Promise<void> => {
  await api.post("/interview/questions", data);
};

export const updateInterviewQuestion = async (data: {
  id: string;
  categoryId?: string;
  question?: string;
  answer?: string;
  difficulty?: number;
  tags?: string;
}): Promise<void> => {
  await api.put("/interview/questions", data);
};

export const deleteInterviewQuestion = async (id: string): Promise<void> => {
  await api.delete(`/interview/questions/${id}`);
};
