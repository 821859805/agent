#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Skill Registry - manages loaded skills and provides lookup functionality.
"""

from typing import Dict, Optional, List
from .skill import Skill


class SkillRegistry:
    """
    Registry for managing loaded skills.

    Skills are organized by name and can be looked up for execution.
    The registry also handles skill deduplication (project skills override user skills).
    """

    def __init__(self):
        # 技能注册器拥有的技能
        self._skills: Dict[str, Skill] = {}

        # 分类别技能路径
        self._skills_by_location: Dict[str, List[Skill]] = {
            "project": [],
            "user": [],
            "managed": [],
        }

    def register(self, skill: Skill) -> bool:
        """
         注册一个技能到注册器里

        项目目录技能优先级大于用户目录技能，项目技能如果与用户技能同名，则技能不会注册

        优先级顺序: project > user > managed

        Args:
            skill: Skill instance to register

        Returns:
            如果注册成功则返回True，如果被跳过了则返回False
        """
        existing = self._skills.get(skill.name)

        if existing:
            # Check priority
            priority = {"project": 0, "user": 1, "managed": 2}
            existing_priority = priority.get(existing.location, 99)
            new_priority = priority.get(skill.location, 99)

            if new_priority >= existing_priority:
                # Skip - existing skill has higher or equal priority
                return False

        self._skills[skill.name] = skill
        self._skills_by_location[skill.location].append(skill)
        return True

    def get(self, name: str) -> Optional[Skill]:
        """
        Get a skill by name.

        Args:
            name: Name of the skill

        Returns:
            Skill instance or None if not found
        """
        return self._skills.get(name)

    def exists(self, name: str) -> bool:
        """
        Check if a skill exists in the registry.

        Args:
            name: Name of the skill

        Returns:
            True if the skill exists
        """
        return name in self._skills

    def list_all(self) -> List[Skill]:
        """
        Get all registered skills.

        Returns:
            List of all skills
        """
        return list(self._skills.values())

    def list_by_location(self, location: str) -> List[Skill]:
        """
        Get skills by location type.

        Args:
            location: Location type (project, user, managed)

        Returns:
            List of skills from that location
        """
        return self._skills_by_location.get(location, [])

    def clear(self):
        """Clear all registered skills."""
        self._skills.clear()
        for location in self._skills_by_location:
            self._skills_by_location[location].clear()

    def generate_skills_prompt(self, char_budget: int = 10000) -> str:
        """
        生成一个列举了所有可用skills的提示词

        限制字符数以管理上下文窗口

        Args:
            char_budget: 技能列表能包含的最大字符数

        Returns:
            格式化后的技能提示词
        """
        skills = self.list_all()

        if not skills:
            return ""

        entries = []
        total_chars = 0

        for skill in skills:
            entry = skill.to_xml()
            if total_chars + len(entry) > char_budget:
                break
            entries.append(entry)
            total_chars += len(entry)

        if not entries:
            return ""

        skills_xml = "\n".join(entries)
        return f"""<available_skills>
{skills_xml}
</available_skills>"""

    def __len__(self) -> int:
        return len(self._skills)

    def __contains__(self, name: str) -> bool:
        return name in self._skills

    def __iter__(self):
        return iter(self._skills.values())


# 全局技能注册器实例
_skill_registry: Optional[SkillRegistry] = None


def get_skill_registry() -> SkillRegistry:
    """
    获取全局技能注册器实例

    如果不存在，创建注册器

    Returns:
        技能注册器实例
    """
    global _skill_registry
    if _skill_registry is None:
        _skill_registry = SkillRegistry()
    return _skill_registry


def reset_skill_registry():
    """Reset the global skill registry."""
    global _skill_registry
    _skill_registry = None
