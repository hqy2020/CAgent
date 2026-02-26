import { api } from "./api";

// ===================== 类型定义 =====================

export interface StudyModule {
  id: string;
  name: string;
  description?: string | null;
  icon?: string | null;
  sortOrder: number;
  enabled: number;
  chapterCount?: number;
  createTime?: string | null;
}

export interface StudyChapter {
  id: string;
  moduleId: string;
  title: string;
  summary?: string | null;
  sortOrder: number;
  documentCount?: number;
  createTime?: string | null;
}

export interface StudyDocument {
  id: string;
  chapterId: string;
  moduleId: string;
  title: string;
  content: string;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface StudyDocumentListItem {
  id: string;
  chapterId: string;
  moduleId: string;
  title: string;
  createTime?: string | null;
}

export interface DocumentNode {
  id: string;
  title: string;
}

export interface ChapterNode {
  id: string;
  title: string;
  documents: DocumentNode[];
}

export interface StudyModuleTree {
  id: string;
  name: string;
  icon?: string | null;
  chapters: ChapterNode[];
}

export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

// ===================== 模块 API =====================

export const getStudyModulesPage = async (
  pageNo = 1,
  pageSize = 50
): Promise<PageResult<StudyModule>> => {
  return api.get<PageResult<StudyModule>, PageResult<StudyModule>>("/study/modules", {
    params: { pageNo, pageSize }
  });
};

export const getStudyModuleTree = async (): Promise<StudyModuleTree[]> => {
  return api.get<StudyModuleTree[], StudyModuleTree[]>("/study/modules/tree");
};

export const createStudyModule = async (data: {
  name: string;
  description?: string;
  icon?: string;
  sortOrder?: number;
  enabled?: number;
}): Promise<void> => {
  await api.post("/study/modules", data);
};

export const updateStudyModule = async (data: {
  id: string;
  name?: string;
  description?: string;
  icon?: string;
  sortOrder?: number;
  enabled?: number;
}): Promise<void> => {
  await api.put("/study/modules", data);
};

export const deleteStudyModule = async (id: string): Promise<void> => {
  await api.delete(`/study/modules/${id}`);
};

// ===================== 章节 API =====================

export const getStudyChapters = async (moduleId: string): Promise<StudyChapter[]> => {
  return api.get<StudyChapter[], StudyChapter[]>("/study/chapters", {
    params: { moduleId }
  });
};

export const createStudyChapter = async (data: {
  moduleId: string;
  title: string;
  summary?: string;
  sortOrder?: number;
}): Promise<void> => {
  await api.post("/study/chapters", data);
};

export const updateStudyChapter = async (data: {
  id: string;
  moduleId?: string;
  title?: string;
  summary?: string;
  sortOrder?: number;
}): Promise<void> => {
  await api.put("/study/chapters", data);
};

export const deleteStudyChapter = async (id: string): Promise<void> => {
  await api.delete(`/study/chapters/${id}`);
};

// ===================== 文档 API =====================

export const getStudyDocumentsPage = async (
  pageNo = 1,
  pageSize = 20,
  chapterId?: string,
  moduleId?: string
): Promise<PageResult<StudyDocumentListItem>> => {
  return api.get<PageResult<StudyDocumentListItem>, PageResult<StudyDocumentListItem>>(
    "/study/documents",
    {
      params: {
        pageNo,
        pageSize,
        chapterId: chapterId || undefined,
        moduleId: moduleId || undefined
      }
    }
  );
};

export const getStudyDocument = async (id: string): Promise<StudyDocument> => {
  return api.get<StudyDocument, StudyDocument>(`/study/documents/${id}`);
};

export const createStudyDocument = async (data: {
  chapterId: string;
  moduleId: string;
  title: string;
  content: string;
}): Promise<void> => {
  await api.post("/study/documents", data);
};

export const updateStudyDocument = async (data: {
  id: string;
  title?: string;
  content?: string;
}): Promise<void> => {
  await api.put("/study/documents", data);
};

export const deleteStudyDocument = async (id: string): Promise<void> => {
  await api.delete(`/study/documents/${id}`);
};
